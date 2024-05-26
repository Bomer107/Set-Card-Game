package bguspl.set.ex;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;
    private int[] playerCardsToRemove;
    private boolean elapsed = false;
    public boolean timer = false;
    private boolean hints;
    


    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    private Thread dThread = null;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;
    private long startTime = 0;
    private final long SECOND = 1000;
    private final long TEN_MILLIE = 10;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.terminate = false;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        playerCardsToRemove = null;

        if(env.config.turnTimeoutMillis == 0)
            elapsed = true;
        if(env.config.turnTimeoutMillis > 0)
            timer = true;
        hints = env.config.hints;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        dThread = Thread.currentThread();
        env.logger.info("thread " + (Thread.currentThread()).getName() + " starting run().");

        lockEntireTable();

        // creating the players Threads
        for (int i = 0; i < players.length; ++i){
            ThreadLogger playerThread = new ThreadLogger(players[i], "player " + i, env.logger);
            players[i].setPlayerThread(playerThread);
            playerThread.startWithLog();
        }

        startTime = System.currentTimeMillis();

        //strating the game
        while (!shouldFinish()) {
            reShuffleDeck();
            placeCardsOnTable();

            releaseEntireTable();

            if(elapsed){
                startTime = System.currentTimeMillis();
            }
            
            timerLoop();

            if(!terminate){
                lockEntireTable();
                while(!table.queueOfPlayers.isEmpty()){
                    checkSet(table.queueOfPlayers.poll());
                    if(playerCardsToRemove != null){
                        removeCardsFromTable();
                        placeCardsOnTable();
                    }
                }
                removeAllCardsFromTable();
            }
        }
        if(!terminate){
            announceWinners();
            terminate();
        }
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        updateTimerDisplay(true);
        boolean printedHints = false;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            
            if(hints && !printedHints){
                table.hints();
                printedHints = true;
            }
            updateTimerDisplay(false);

            if(!timer && table.setsAvailable().isEmpty()){
                return;
            }
    
            sleepUntilWokenOrTimeout(); // wake up and check if there is a set

            if(playerCardsToRemove != null){
                printedHints = false;
                changeBoard();
            }
                
            else
                updateTimerDisplay(false);
            
            
        }
    }

    private void changeBoard(){
        acquireLocks(playerCardsToRemove);
        updateTimerDisplay(true);
        removeCardsFromTable();
        placeCardsOnTable();

        if(elapsed)
            startTime = System.currentTimeMillis();
        
        releaseLocks(playerCardsToRemove);
        playerCardsToRemove = null;
    
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for(int playerId = players.length - 1; playerId > -1; --playerId)
        {
            Player player = players[playerId];
            player.terminate();
        }
        terminate = true;
        dThread.interrupt();
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).isEmpty();
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        for(int i = 0; i < playerCardsToRemove.length; ++i){
            for(Player player : players){
                player.tokenRemove(playerCardsToRemove[i]);
            }
        }
        
        table.removeCards(playerCardsToRemove);
        env.logger.info("sizeOfDeck " + deck.size());
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        env.logger.info("placing cards on the table");
        table.placeCards(deck);
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        Player player = null;
        if(timer){
            try{
                player = table.queueOfPlayers.poll(calculateSleep(), TimeUnit.MILLISECONDS);
            }catch(InterruptedException wakeUp){dThread.interrupt(); return;}
        }
        else if (elapsed){
            try {
                player = table.queueOfPlayers.poll(calculateSleepElapsed(), TimeUnit.MILLISECONDS);
            }catch(InterruptedException wakeUp){dThread.interrupt(); return;}
        }  

        else{
            try {
                player = table.queueOfPlayers.take();
            }catch(InterruptedException wakeUp){dThread.interrupt(); return;}
        }
        
        checkSet(player);
    }

    private long calculateSleep(){
        long nextWakeUp;
        if(reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis)
            nextWakeUp = TEN_MILLIE;
        else
            nextWakeUp = SECOND;

        long elapsedTime = System.currentTimeMillis() - startTime;
        long sleep = nextWakeUp - (elapsedTime % nextWakeUp);
        return sleep;
    }

    private long calculateSleepElapsed(){
        long elapsedTime = System.currentTimeMillis() - startTime;
        long sleep = SECOND - (elapsedTime % SECOND);
        return sleep;
    }
    
    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(timer){
            boolean warn = false;
            if(!reset){
                long timeLeft = reshuffleTime - System.currentTimeMillis();
                long roundedTimeLeft = timeLeft - (TEN_MILLIE / 2);
                
                if(roundedTimeLeft < 0){
                    env.ui.setCountdown(0, true);
                    return;
                }

                if(timeLeft < env.config.turnTimeoutWarningMillis){
                    env.ui.setCountdown(roundedTimeLeft, true); //we know that the time will be almost accureate. so we round it
                    return;
                }

                roundedTimeLeft = timeLeft - (SECOND / 2);
                    
                if(roundedTimeLeft < 0){
                    env.ui.setCountdown(0, true);
                    return;
                }
                
                else
                    env.ui.setCountdown(roundedTimeLeft, false); //we know that the time will be almost accureate. so we round it
            }
            else{
                startTime = System.currentTimeMillis();
                reshuffleTime = startTime + env.config.turnTimeoutMillis - 1;
                if(env.config.turnTimeoutMillis < env.config.turnTimeoutWarningMillis)
                    warn = true;
                env.ui.setCountdown(env.config.turnTimeoutMillis - 1, warn);
            }
        }
        else if(elapsed){
            long time = System.currentTimeMillis() - startTime;
            env.ui.setElapsed(time + (SECOND / 2)); //we know that the time will be almost accureate. so we round it
            return;
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        for(Player player: players){
            player.newBoard();
        }
        table.removeAllCardsFromTable(deck);
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int numPlayersWon = 0;
        int maxScore = 0;
        int[] playersWonIds = new int[players.length];
        for(int id : playersWonIds)
            playersWonIds[id] = -1;

        for(Player player: players){
            if(player.score() == maxScore){
                numPlayersWon++;
                playersWonIds[numPlayersWon - 1] = player.id;
            }
            else if(player.score() > maxScore){
                numPlayersWon = 1;
                playersWonIds[numPlayersWon - 1] = player.id;
                maxScore = player.score();
            }
        }
        int [] winners = new int[numPlayersWon];
        for(int winner = numPlayersWon - 1; winner > -1; --winner)
            winners[winner] = playersWonIds[winner];
            
        env.ui.announceWinner(winners);
    }

    private void reShuffleDeck(){
        Collections.shuffle(deck);
    }

    private void checkSet(Player player){
        if(player == null)
            return;
        BlockingQueue<Integer> waitingOfPlayer = player.getWaitingZone();
        if(env.util.testSet(player.getCards())){
            playerCardsToRemove = player.getTokens();
            player.point();
        }
        else{
            player.penalty();
        }
        waitingOfPlayer.offer(0);
    }

    private void acquireLocks(int[] slots){
        for(int i = 0; i < slots.length; ++i){
            Semaphore sem = table.locks[slots[i]];
            try {
                sem.acquire();
            } catch (InterruptedException e) {dThread.interrupt(); return;}
        }
        
    }

    private void releaseLocks(int[] slots){
        for(int i = 0; i < slots.length; ++i){
            Semaphore sem = table.locks[slots[i]];
            sem.release();
        }
    }

    private void lockEntireTable(){
        for(int i = 0; i < players.length; i++){
            players[i].setStopInput(true);
        }
        Semaphore[] semaphores = table.locks;
        for(int i = 0; i < semaphores.length; ++i){
            Semaphore sem = semaphores[i];
            try {
                sem.acquire();
            } catch (InterruptedException ignore) {dThread.interrupt(); return;}
        }
    }

    private void releaseEntireTable(){
        for(int i = 0; i < players.length; i++){
            players[i].setStopInput(false);
        }
        Semaphore[] semaphores = table.locks;
        for(int i = 0; i < semaphores.length; ++i){
            Semaphore sem = semaphores[i];
            sem.release();
        }
    }



}
