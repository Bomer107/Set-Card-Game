package bguspl.set.ex;

import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

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
    private ThreadLogger aiThread;

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

    private final ConcurrentLinkedQueue<Integer> tokens;

    private final BlockingQueue<Integer> keyInputQueue;
    private final BlockingQueue<Integer> waitingOfPlayer;

    private volatile long freeze;
    private final static long SECOND = 1000L;
    private int legalSetSize;

    private volatile boolean stopInput = false;

    /**
     * The dealer of the game.
     */
    final Dealer dealer;

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
        legalSetSize = env.config.featureSize;

        this.tokens = new ConcurrentLinkedQueue<Integer>();
        this.keyInputQueue = new ArrayBlockingQueue<>(legalSetSize);
        this.waitingOfPlayer = new ArrayBlockingQueue<Integer>(1);
        this.freeze = 0;    
        playerThread = null;
        
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
        if (!human) {
        createArtificialIntelligence();
        }

        while (!terminate) {
            
            executeAction();
            
            while(freeze > 0 && !terminate){
                env.ui.setFreeze(id, freeze);
                
                long timeToSleep = freeze < SECOND ? freeze : SECOND;
                try{Thread.sleep(timeToSleep);} catch(InterruptedException e){}
                
                freeze -= SECOND;
                
                if(freeze <= 0){  
                    env.ui.setFreeze(id, 0);
                }
            }
            
            stopInput = false;
            
        }
            
        if (!human) try { aiThread.joinWithLog();} catch (InterruptedException ignored) {}
        env.logger.info("thread " + (Thread.currentThread()).getName() + " ending it's run() function.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new ThreadLogger(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random rand = new Random();
            while (!terminate) {
                keyPressed(rand.nextInt(env.config.tableSize));
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id, env.logger);
        aiThread.startWithLog();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        playerThread.interrupt();
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
        if(!stopInput)
            keyInputQueue.offer(slot);
    }   
       

    public void executeAction(){
        int slot = 0;
        try {
            slot = keyInputQueue.take();
        } catch (InterruptedException Interrupted) {return;}

        if(tokens.contains(slot) && !terminate){
            table.removeToken(this, slot);
            return;
        }
        
        if(tokens.size() < legalSetSize && !terminate){
            table.placeToken(this, slot);     
        }
        
    }

    public void placeToken(int slot){
        tokens.add(slot);
    }

    public int[] getCards()
    {
        if(tokens.size() == legalSetSize){
            int [] getCards = new int[legalSetSize];
            Iterator<Integer> iter = tokens.iterator();
            int tokenNum = 0;
            while (iter.hasNext())
                getCards[tokenNum++] = table.slotToCard(iter.next());
            return getCards;
        }
        return null;
    }

    public int[] getTokens(){

        int [] getTokens = new int[env.config.featureSize];
        Iterator<Integer> iter = tokens.iterator();
        int tokenNum = 0;
        while (iter.hasNext())
            getTokens[tokenNum++] = iter.next();
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
        @SuppressWarnings("unused")
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.logger.info("player got point");
        setFreeze(env.config.pointFreezeMillis);
        clearTokens();
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        env.logger.info("player got penalty");
        setFreeze(env.config.penaltyFreezeMillis);
    }

    public int score() {
        return score;
    }

    public boolean tokenRemove(int tokenToRemove){
        return tokens.remove(tokenToRemove);
    }

    public void newBoard(){
        clearTokens();
        clearKeyInput();
    }

    public long getFreeze(){
        return freeze;
    }

    public void setFreeze(long freeze){
        this.freeze = freeze; 
    }

    public void clearTokens(){
        tokens.clear();
    }

    public void clearKeyInput(){
        keyInputQueue.clear();
    }


    //for debuging
    public void printTokens(){
        Iterator<Integer> iter = tokens.iterator();
        while(iter.hasNext()){
            System.out.print(iter.next());
            System.out.print(" - ");
        }
        System.out.println();
    }

    public void enterWaitingZone(){
        try {
            waitingOfPlayer.take();
        } catch (InterruptedException e) {}
    }

    public BlockingQueue<Integer> getWaitingZone(){
        return waitingOfPlayer;
    }

    public void setStopInput(boolean censor){
        stopInput = censor;
    }

}
