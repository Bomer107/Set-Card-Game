package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;
import bguspl.set.ThreadLogger;

import java.util.List;
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
    private final ThreadLogger[] playersThreads;

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
        playersThreads = new ThreadLogger[players.length];
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting run().");
        
        // creating the players Threads
        for (int i = 0; i < players.length; ++i){
            playersThreads[i] = new ThreadLogger(players[i], "player " + i, env.logger);
            playersThreads[i].startWithLog();
        }

        reShuffleDeck();

        //strating the game
        while (!shouldFinish()) {     
            placeCardsOnTable();
            try {
                // shutdown stuff
            
            playersThreads[0].joinWithLog();
        } catch (InterruptedException ignored) {}
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime=System.currentTimeMillis()+reshuffleTime;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate=false;
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
        // TODO implement
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        int firstSlot = 0;
        for (int slot = firstSlot; slot < env.config.tableSize; ++slot)
            if(table.slotToCard(slot) == null){
                Integer card = deck.remove(deck.size() - 1);
                table.placeCard(card, slot);
            }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try {
            synchronized(this){
                this.wait(env.config.turnTimeoutMillis);
            }
        } catch (InterruptedException e) {}
    }
    
    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset)
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        else{
            boolean warn = false;
            if(true)
                warn = true;
            env.ui.setCountdown(env.config.turnTimeoutMillis - System.currentTimeMillis(), warn);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // TODO implement
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
    }

    private void reShuffleDeck(){
        Collections.shuffle(deck);
    }
}
