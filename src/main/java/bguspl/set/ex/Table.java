package bguspl.set.ex;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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

    public final LinkedBlockingQueue<Player> queueOfPlayers;
    public final Semaphore[] locks;
    public final Semaphore queueSem;

    private int legalSetSize;



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
        this.queueOfPlayers = new LinkedBlockingQueue<Player>();
        this.locks = new Semaphore[env.config.tableSize];

        for(int i = 0; i < locks.length; ++i){
            locks[i] = new Semaphore(1, true);
        }

        this.queueSem = new Semaphore(1, true);
        legalSetSize = env.config.featureSize;
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }


    public List<int[]> setsAvailable(){
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        List<int[]> sets = env.util.findSets(deck, Integer.MAX_VALUE);
        return sets;
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
       List<int[]> sets = setsAvailable();
        if(!sets.isEmpty()){
            sets.forEach(set -> {
                StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
                List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
                int[][] features = env.util.cardsToFeatures(set);
                System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
            });
        }
        else
            System.out.println("There are no possible sets on this board");
            
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
        } catch (InterruptedException e) {Thread.currentThread().interrupt(); return;}

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
        } catch (InterruptedException ignored) {Thread.currentThread().interrupt(); return;}

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

        try {
            queueSem.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        Iterator<Player> iter = queueOfPlayers.iterator();
        while(iter.hasNext()){
            Player player = iter.next();
            if(player.getNumTokens() != 3){
                iter.remove();
                player.getWaitingZone().offer(0);
            }
        }
        queueSem.release();
    
    }

    public void removeAllCardsFromTable(List<Integer> deck){
        env.logger.info("Deleting all cards from the table");

        for(int slot = 0; slot < env.config.tableSize; ++slot){
            if(slotToCard(slot) != null){
                deck.add(slotToCard(slot));
                removeCard(slot);
            }
        }       
    }   

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        env.ui.placeToken(player, slot);
    }

    //sync with sem
    public void placeToken(Player player, int slot){
        Semaphore sem = locks[slot];
        try {
            sem.acquire();
        } catch (InterruptedException e) {
            return;
        }

        if(slotToCard(slot) == null){
            sem.release();
            return;
        }

        placeToken(player.id, slot);
        player.placeToken(slot);

        if(player.getNumTokens() == legalSetSize){
            player.setStopInput(true);
            try {
                queueSem.acquire();
            } catch (InterruptedException e) {
                sem.release();
                return;
            }
            sem.release();
            
            if(player.getNumTokens() == legalSetSize)
                queueOfPlayers.offer(player);

            queueSem.release();
            player.enterWaitingZone();
        }
        else
            sem.release();

    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     *///sync with sem
    public boolean removeToken(int player, int slot) {
        boolean tokenRemoved = false;
        if(slotToCard[slot] != null){
            env.ui.removeToken(player, slot);
            tokenRemoved = true;
        }
        return tokenRemoved;
    }

    public boolean removeToken(Player player, int slot){
        Semaphore sem = locks[slot];
        try {
            sem.acquire();
        } catch (InterruptedException interrupted) {return false;}

        boolean tokenRemoved = removeToken(player.id, slot);
        if(tokenRemoved){
            player.tokenRemove(slot);
        }
    
        sem.release();
        return tokenRemoved;
    }

    public Integer cardToSlot(Integer card){
        return cardToSlot[card];
    }

    public Integer slotToCard(Integer slot){
        return slotToCard[slot];
    }

    // for debugging
    public void printQueueOfPlayers(){
        Iterator<Player> iter = queueOfPlayers.iterator();
        while(iter.hasNext()){
            System.out.print(iter.next());
            System.out.print(" - ");
        }
        System.out.println();
    }

}
