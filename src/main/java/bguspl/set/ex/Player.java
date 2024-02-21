package bguspl.set.ex;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;
/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private LinkedList<Integer> tokens;

    private LinkedList<Integer> keyInputQueue;

    public long freeze;
    private final long SECOND = 1000L;
    private volatile boolean dontGetInput;
    private volatile boolean waiting;

    /**
     * The dealer of the game.
     */
    Dealer dealer;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.dealer = dealer;
        this.table = table;
        this.id = id;
        this.human = human;
        this.tokens = new LinkedList<Integer>();
        this.keyInputQueue = new LinkedList<Integer>();
        this.freeze = 0;    
        playerThread = null;
        dontGetInput = false;
        waiting = false;
    }

    public void setPlayerThread(ThreadLogger playerThread){
        this.playerThread = playerThread;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting it's run() function.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            try {
                synchronized(this){
                    waiting = true; 
                    wait();}
                } catch (Exception ignored) {}
            waiting = false;

            while(freeze > 0 && !terminate){
                env.ui.setFreeze(id, freeze);
                synchronized(this){

                    try{env.logger.info("player got freezed");
                        wait(SECOND);} catch(InterruptedException e){}

                    freeze -= SECOND;
                }
                if(freeze == 0)
                    env.ui.setFreeze(id, freeze);
            }
            synchronized(table){
                executeAction();
            } 
        }
            
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " ending it's run() function.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random rand = new Random();
            while (!terminate) {
                keyPressed(rand.nextInt(11));
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        try{
            ((ThreadLogger)playerThread).joinWithLog();
        } catch (InterruptedException e) {}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
            if(freeze > 0 || getDontGetInput())
                return;
            if(keyInputQueue.size() < 4){
                if(keyInputQueue.size() == 3)
                    keyInputQueue.poll();
                keyInputQueue.add(slot);
                synchronized(this){
                    if(waiting)
                        this.notify();
                }
            }
        
    }   

    public void executeAction(){
        if(keyInputQueue.isEmpty())
            return;
        int slot = keyInputQueue.poll();
        Iterator<Integer> iter = tokens.iterator();
        while(iter.hasNext()){
            Integer token = iter.next();
            if(token.equals(slot)){
                table.removeToken(id, slot);
                iter.remove();
                return;
            }
        }
        if(tokens.size() < env.config.featureSize){
            table.placeToken(this, slot);
        }
    }

    public void placeToken(int slot){
        tokens.add(slot);
    }

    public int[] getCards()
    {
        int [] getCards = new int[env.config.featureSize];
        Iterator<Integer> iter = tokens.iterator();
        int tokenNum = 0;
        while (iter.hasNext())
            getCards[tokenNum++] = table.slotToCard(iter.next());
        return getCards;
    }

    public int[] getTokens(){
        int [] getTokens = new int[env.config.featureSize];
        Iterator<Integer> iter = tokens.iterator();
        int tokenNum = 0;
        while (iter.hasNext())
            getTokens[tokenNum++] = (iter.next()).intValue();
        return getTokens;
    }

    public int getNumTokens(){
        return tokens.size();
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        freeze = env.config.pointFreezeMillis;
        tokens.clear();
        keyInputQueue.clear();
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        freeze = env.config.penaltyFreezeMillis;
    }

    public int score() {
        return score;
    }

    public void tokenRemoved(int tokenToRemove){
        Iterator<Integer> iter = tokens.iterator();
        while(iter.hasNext()){
            Integer token = iter.next();
            if(token.equals(tokenToRemove))
                iter.remove();
        }
    }

    public void newBoard(){
        tokens.clear();
        keyInputQueue.clear();
    }

    public synchronized void setDontGetInput(boolean dontGetInput){
        this.dontGetInput = dontGetInput;
    }

    public synchronized boolean getDontGetInput(){
        return dontGetInput;
    }

    public synchronized boolean getWaiting(){
        return waiting;
    }
}
