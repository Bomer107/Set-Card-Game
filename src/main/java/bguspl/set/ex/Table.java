package bguspl.set.ex;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import bguspl.set.Env;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    public final LinkedList<Player> queueOfPlayers;
    public Semaphore queueOfChange;
    private volatile boolean newBoard;

    private static int LEGAL_SET_SIZE = 3;



    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.queueOfPlayers = new LinkedList<Player>();
        this.queueOfChange = new Semaphore(1, true);
        this.newBoard = false;

    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
        System.out.println("------------------------------------------------------------------------------------------");
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        
        env.ui.placeCard(card, slot);
    }

    public void placeCards(List<Integer> deck){
        for (int slot = 0; slot < env.config.tableSize && !deck.isEmpty(); ++slot)
            if (slotToCard(slot) == null){
                Integer card = deck.remove(0);
                placeCard(card, slot);
            }
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        Integer card = slotToCard[slot];
        slotToCard[slot] = null;
        cardToSlot[card] = null;
        env.ui.removeTokens(slot);
        env.ui.removeCard(slot);
    }

    public void removeCards(int[] playerTokens) {
        for(int token : playerTokens){
            removeCard(token);
        }
        Iterator<Player> iter = queueOfPlayers.iterator();
        while(iter.hasNext()){
            Player player = iter.next();
            if(player.getNumTokens() != 3){
                iter.remove();
                System.out.println("player " + player.id + " got removed from the queue");
                synchronized(player){
                    player.setFreeze(0);
                    player.notify();
                    System.out.println("dealer " + player.id + " notifies player");
                }
            }
        }
    }

    public void removeAllCardsFromTable(List<Integer> deck){
        env.logger.info("Deleting all cards from the table");

        for(int slot = 0; slot < env.config.tableSize; ++slot){
            if(slotToCard(slot) != null){
                deck.add(slotToCard(slot));
                removeCard(slot);
            }
        }

        //setNewBoard(true);
        //while()
        
    }   

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        env.ui.placeToken(player, slot);
    }

    //sync with queueOfChange
    public void placeToken(Player player, int slot){
        try {
            queueOfChange.acquire();
            env.logger.info("player " + player.id + " acquires semaphore");
            //System.out.println("player " + player.id + " acquires semaphore");
        }catch(InterruptedException wakeUp){return;}

        if(slotToCard(slot) == null /*|| IsNewBoard()*/){
            queueOfChange.release();
            return;
        }
        
        placeToken(player.id, slot);
        player.placeToken(slot);

        if(player.getNumTokens() == LEGAL_SET_SIZE){
            //env.logger.info("player " + player.id + " added to queue");
            env.logger.info("player " + player.id + " releases semaphore");
            //System.out.println("player " + player.id + " releases semaphore");
            player.setFreeze(1000);
            synchronized(player){
                queueOfPlayers.offer(player);
                queueOfChange.release();
                System.out.println("player " + player.id + " added to queue");
                synchronized(player.dealer){
                    (player.dealer).notify();
                }
                try {
                    System.out.println(player.id + " going to sleep");
                    player.wait();
                } catch (InterruptedException wakeUp) {}
                System.out.println(player.id + " wakes up");
            } 
        }
        else{
            env.logger.info("player " + player.id + " releases semaphore");
            queueOfChange.release();
            //System.out.println("player " + player.id + " releases semaphore"); 
        }
        
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     *///sync with queueOfChange
    public boolean removeToken(int player, int slot) {
        boolean tokenRemoved = false;
        if(slotToCard[slot] != null){
            env.ui.removeToken(player, slot);
            tokenRemoved = true;
        }
        return tokenRemoved;
    }

    public boolean removeToken(Player player, int slot){
        try {
            queueOfChange.acquire();
        } catch (InterruptedException interrupted) {return false;}

        boolean tokenRemoved = removeToken(player.id, slot);
        if(tokenRemoved){
            player.tokenRemove(slot);
        }
    
        queueOfChange.release();
        return tokenRemoved;
    }

    public Integer cardToSlot(Integer card){
        return cardToSlot[card];
    }

    public Integer slotToCard(Integer slot){
        return slotToCard[slot];
    }

    public boolean IsNewBoard(){
        return newBoard;
    }

    public void setNewBoard(boolean dealerCreatingNewBoard){
        newBoard = dealerCreatingNewBoard;
    }

    public LinkedList<Player> getQueueOfPlayers(){
        return queueOfPlayers;
    }

    public void printQueueOfPlayers(){
        Iterator<Player> iter = queueOfPlayers.iterator();
        while(iter.hasNext()){
            System.out.print(iter.next());
            System.out.print(" - ");
        }
        System.out.println();
    }

}
