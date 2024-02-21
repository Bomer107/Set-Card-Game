package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import java.util.Collections;

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
    private static final long TIME_TO_UPDATE_CLOCK = 10;
    private int[] playerCardsToRemove;


    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.terminate = false;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        playerCardsToRemove = null;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting run().");
        
        // creating the players Threads
        for (int i = 0; i < players.length; ++i){
            ThreadLogger playerThread = new ThreadLogger(players[i], "player " + i, env.logger);
            players[i].setPlayerThread(playerThread);
            playerThread.startWithLog();
        }

        //strating the game
        while (!shouldFinish()) {     
            reShuffleDeck();
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis - 1;
        env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            if(env.config.hints)
                table.hints();
            if(playerCardsToRemove == null)
                updateTimerDisplay(false);
            else
                updateTimerDisplay(true);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for(int playerId = players.length - 1; playerId > -1; --playerId)
        {
            Player player = players[playerId];
            while(!player.getWaiting()){}
            synchronized(player){
                player.notify();
            }
            player.terminate();
        }
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if(playerCardsToRemove != null)
            table.removeCards(playerCardsToRemove);
        env.logger.info("sizeOfDeck " + deck.size());
        playerCardsToRemove = null;
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
    private synchronized void sleepUntilWokenOrTimeout() {
        try {
            this.wait(TIME_TO_UPDATE_CLOCK);  
        } catch (InterruptedException e) {}
        checkSet(table.queueOfPlayers);
    }
    
    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset){
            env.ui.setCountdown(env.config.turnTimeoutMillis - 1, false);
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis - 1;
        }
        else{
            boolean warn = false;
            long time = reshuffleTime - System.currentTimeMillis();
            if(time < 0){
                env.ui.setCountdown(0, true);
                return;
            }
            if(time <= env.config.turnTimeoutWarningMillis)
                warn = true;
            env.ui.setCountdown(time, warn);
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
        playerCardsToRemove = null;
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

    private void checkSet(Queue<Player> queueOfPlayers){
        synchronized(table){
            if(!queueOfPlayers.isEmpty()){
                env.logger.info("dealer checks cards");
                Player player = queueOfPlayers.poll();
                if(env.util.testSet(player.getCards())){
                    playerCardsToRemove = player.getTokens();
                    player.point();
                }
                else{
                    player.penalty();
                }
                while(!player.getWaiting()){}
                synchronized(player){
                    env.logger.info("notifing the player he got his evaluation");
                    player.setDontGetInput(false);
                    player.notify();
                }
            }   
        }
    }
}
