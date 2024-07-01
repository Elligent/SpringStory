package com.dori.SpringStory;

import com.dori.SpringStory.client.MapleClient;
import com.dori.SpringStory.client.character.MapleChar;
import com.dori.SpringStory.connection.netty.ChannelAcceptor;
import com.dori.SpringStory.connection.netty.ChannelHandler;
import com.dori.SpringStory.connection.netty.ChatAcceptor;
import com.dori.SpringStory.connection.netty.LoginAcceptor;
import com.dori.SpringStory.connection.packet.handlers.ChatHandler;
import com.dori.SpringStory.enums.ServiceType;
import com.dori.SpringStory.events.EventManager;
import com.dori.SpringStory.dataHandlers.MobDropHandler;
import com.dori.SpringStory.scripts.handlers.ScriptHandler;
import com.dori.SpringStory.services.*;
import com.dori.SpringStory.temporaryStats.characters.BuffDataHandler;
import com.dori.SpringStory.world.MapleChannel;
import com.dori.SpringStory.world.MapleWorld;
import com.dori.SpringStory.logger.Logger;
import com.dori.SpringStory.world.MigrateInUser;
import com.dori.SpringStory.dataHandlers.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.dori.SpringStory.constants.ServerConstants.*;
import static com.dori.SpringStory.constants.ServerConstants.CHANNELS_PER_WORLD;

@Component
public class Server {
    // Logger -
    private static final Logger logger = new Logger(Server.class);
    // World list -
    private static final Map<Integer, MapleWorld> worldList = new ConcurrentHashMap<>();
    // Migrate in users -
    private static final Map<Integer, MigrateInUser> migrateUsers = new HashMap<>();
    // List of Connected clients -
    private static final Map<Integer, MapleClient> connectedClients = new ConcurrentHashMap<>();

    public static Map<Integer, MapleWorld> getWorldsMap() {
        return worldList;
    }

    public static List<MapleWorld> getWorlds() {
        return worldList.values().stream().toList();
    }

    public static MapleWorld getWorldById(int worldID) {
        return getWorldsMap().getOrDefault(worldID, null);
    }

    public static boolean isChrOnline(int chrID) {
        return connectedClients
                .values()
                .stream()
                .filter(client -> client.getChr().getId() == chrID)
                .findFirst()
                .orElse(null) != null;
    }

    public static boolean isAccountAlreadyConnected(int accountID) {
        return connectedClients.getOrDefault(accountID, null) != null;
    }

    public static void addNewConnectedClient(int accountID,
                                             @NotNull MapleClient client) {
        connectedClients.put(accountID, client);
    }

    public static void removeConnectedAccount(int accountID) {
        connectedClients.remove(accountID);
    }

    private static void initMapleWorlds() {
        worldList.put((int) DEFAULT_WORLD_ID, new MapleWorld(DEFAULT_WORLD_ID, WORLD_NAME, EVENT_MSG, CHANNELS_PER_WORLD));
        for (MapleWorld world : getWorlds()) {
            for (MapleChannel mapleChannel : world.getChannelList()) {
                ChannelAcceptor ca = new ChannelAcceptor();
                ca.mapleChannel = mapleChannel;
                new Thread(ca).start();
            }
        }
    }

    private static void initAcceptors() {
        // Start Login acceptor -
        new Thread(new LoginAcceptor()).start();
        // Start Chat acceptor -
        new Thread(new ChatAcceptor()).start();
    }

    private static void loadWzData(ExecutorService executorService) {
        executorService.submit(MapDataHandler::load);
        executorService.submit(ItemDataHandler::load);
        executorService.submit(SkillDataHandler::load);
        executorService.submit(StringDataHandler::load);
        executorService.submit(MobDataHandler::load);
        executorService.submit(NpcDataHandler::load);
        executorService.submit(CharacterCosmeticsDataHandler::load);
        // Custom buffs load data -
        executorService.submit(BuffDataHandler::loadBuffsData);
        // Load custom json data -
        executorService.submit(MobDropHandler::load);
        //TODO: next is  QUESTS
    }

    private static void registerServices() {
        ServiceManager.registerNewService(ServiceType.Equip, EquipService.getInstance());
        ServiceManager.registerNewService(ServiceType.Inventory, InventoryService.getInstance());
        ServiceManager.registerNewService(ServiceType.Item, ItemService.getInstance());
        ServiceManager.registerNewService(ServiceType.Account, MapleAccountService.getInstance());
        ServiceManager.registerNewService(ServiceType.Character, MapleCharService.getInstance());
        ServiceManager.registerNewService(ServiceType.StringData, StringDataService.getInstance());
    }

    public static void shutdown() {
        logger.serverNotice("Starting Server shutdown!");
        for (MapleWorld world : getWorlds()) {
            world.shutdown();
        }
        // Clear all the active events -
        EventManager.getAllActiveEvents().forEach(event -> event.cancel(true));
        logger.serverNotice("~ Finished Server shutdown ~");
    }

    public static void startupServer() {
        // Mark if the Server is ready or not -
        boolean isReady = false;
        // Thread service executor to load all the wz data simultaneously -
        ExecutorService executorService = Executors.newFixedThreadPool(AMOUNT_OF_LOADERS);

        logger.serverNotice("Start Loading Server...");
        // Register all the Packet handlers -
        ChannelHandler.initHandlers(false);
        // Register all the Npc scripts -
        ScriptHandler.initHandlers();
        // Init Login & Chat acceptors -
        initAcceptors();
        // Init all the MapleWorlds (including channels) -
        initMapleWorlds();
        // Init Commands -
        ChatHandler.initCmdMap();
        // Init Goto Maps - TODO: need to remove in the future!!!
        MapDataHandler.initGoToMaps();
        // Load all the WZ data -
        loadWzData(executorService);
        // Register all the DB services -
        registerServices();
        try {
            // Stop the executor from receiving new tasks -
            executorService.shutdown();
            // Wait till the server will finish load all the WZ data and then let the server continue the operations -
            isReady = executorService.awaitTermination(MAX_LOADING_TIME_IN_MIN, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.error("Couldn't finish loading the server in the time frame, need to re-run the server, or update the max loading time!");
        }
        if (isReady) {
            logger.serverNotice("~ Server is Ready ~");
        } else {
            // Close the Server if it wasn't able to load correctly -
            System.exit(1);
        }
    }

    public static void migrateInNewUser(MapleClient client) {
        MigrateInUser migrateInUser = new MigrateInUser(client.getAccount(), client.getMapleChannelInstance(), client.getWorldId(), client.getMachineID());
        migrateUsers.put(client.getAccount().getId(), migrateInUser);
    }

    public static void addNewOnlineUser(MapleChar chr, MapleClient client) {
        MigrateInUser migrateInUser = migrateUsers.get(chr.getAccount().getId());
        // Verify the user was migrated properly -
        if (migrateInUser != null) {
            // Set Client data to have to migrate data -
            client.setWorldId((byte) migrateInUser.getWorldID());
            client.setMachineID(migrateInUser.getMachineID());
            client.setChannel((byte) migrateInUser.getChannel().getChannelId());
            client.setMapleChannelInstance(migrateInUser.getChannel());
            client.setAccount(chr.getAccount());
            // Add the char into the channel list of characters -
            client.getMapleChannelInstance().addChar(chr);
            // Set the char to be the client character instance -
            client.setChr(chr);
            // Verify the chr have the link to the updated client -
            chr.setMapleClient(client);
            // Remove from the list of users that need to migrate -
            migrateUsers.remove(chr.getId());
            // Add to the list of connected clients -
            addNewConnectedClient(chr.getAccount().getId(), client);
        } else {
            // trying to log in with a char that wasn't migrate in ?
            client.close();
        }
    }

    public static boolean isWorldExist(int worldID) {
        return getWorldById(worldID) != null;
    }

    public static void removeClient(MapleClient client) {
        connectedClients.remove(client.getAccount().getId());
    }

}
