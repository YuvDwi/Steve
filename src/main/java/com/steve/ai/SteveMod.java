package com.steve.ai;

import com.mojang.logging.LogUtils;
import com.steve.ai.command.SteveCommands;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.dashboard.PlanDashboardServer;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.event.EventBus;
import com.steve.ai.event.SimpleEventBus;
import com.steve.ai.event.plan.PlanEvent;
import com.steve.ai.mcp.MCPToolRegistry;
import com.steve.ai.memory.KnowledgeBootstrapper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Consumer;

@Mod(SteveMod.MODID)
public class SteveMod {
    public static final String MODID = "steve";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<EntityType<?>> ENTITIES = 
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    public static final RegistryObject<EntityType<SteveEntity>> STEVE_ENTITY = ENTITIES.register("steve",
        () -> EntityType.Builder.of(SteveEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .build("steve"));

    private static SteveManager steveManager;

    private static final EventBus PLAN_BUS = new SimpleEventBus();
    private static PlanDashboardServer dashboardServer;
    private static net.minecraft.server.MinecraftServer server;

    public SteveMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ENTITIES.register(modEventBus);

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SteveConfig.SPEC);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::entityAttributes);

        MinecraftForge.EVENT_BUS.register(this);
        
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            MinecraftForge.EVENT_BUS.register(com.steve.ai.client.SteveGUI.class);        }
        
        steveManager = new SteveManager();
    }

    private void commonSetup(final FMLCommonSetupEvent event) {    }

    private void entityAttributes(EntityAttributeCreationEvent event) {
        event.put(STEVE_ENTITY.get(), SteveEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onCommandRegister(RegisterCommandsEvent event) {        SteveCommands.register(event.getDispatcher());    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MCPToolRegistry.init();
        KnowledgeBootstrapper.syncLocalKnowledgeToMempalace();
        server = event.getServer();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (dashboardServer != null) {
            try {
                dashboardServer.stop();
            } catch (Exception e) {
                LOGGER.warn("Failed to stop plan dashboard server: {}", e.getMessage());
            }
            dashboardServer = null;
        }
        server = null;
    }

    /** Active {@link net.minecraft.server.MinecraftServer}, or null if no
     *  server is running. Held so the dashboard HTTP handler (which runs on
     *  the HttpServer's executor threads) can hop back to the main server
     *  thread before touching {@link SteveEntity} state. */
    public static net.minecraft.server.MinecraftServer getServer() {
        return server;
    }

    public static SteveManager getSteveManager() {
        return steveManager;
    }

    /** Global event bus for {@link PlanEvent}s. Independent of the per-entity
     *  bus in {@code ActionExecutor} because a plan is global across all Steves. */
    public static EventBus getPlanEventBus() {
        return PLAN_BUS;
    }

    /** Subscribe a single consumer to every concrete {@link PlanEvent} subtype.
     *  {@code SimpleEventBus} dispatches by exact runtime class, so we register
     *  one subscription per concrete class. Returns a list of {@link com.steve.ai.event.EventBus.Subscription}s
     *  for unsubscribing. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<com.steve.ai.event.EventBus.Subscription> subscribeToAllPlanEvents(Consumer<PlanEvent> consumer) {
        return List.of(
            PLAN_BUS.subscribe(com.steve.ai.event.plan.PlanCreatedEvent.class,         (com.steve.ai.event.plan.PlanCreatedEvent e) -> consumer.accept(e)),
            PLAN_BUS.subscribe(com.steve.ai.event.plan.PlanDesignReadyEvent.class,     (com.steve.ai.event.plan.PlanDesignReadyEvent e) -> consumer.accept(e)),
            PLAN_BUS.subscribe(com.steve.ai.event.plan.PlanPhaseChangedEvent.class,    (com.steve.ai.event.plan.PlanPhaseChangedEvent e) -> consumer.accept(e)),
            PLAN_BUS.subscribe(com.steve.ai.event.plan.PlanApprovedEvent.class,        (com.steve.ai.event.plan.PlanApprovedEvent e) -> consumer.accept(e)),
            PLAN_BUS.subscribe(com.steve.ai.event.plan.PlanHaltedEvent.class,          (com.steve.ai.event.plan.PlanHaltedEvent e) -> consumer.accept(e)),
            PLAN_BUS.subscribe(com.steve.ai.event.plan.PlanLogEvent.class,             (com.steve.ai.event.plan.PlanLogEvent e) -> consumer.accept(e)),
            PLAN_BUS.subscribe(com.steve.ai.event.plan.PlanChatEvent.class,            (com.steve.ai.event.plan.PlanChatEvent e) -> consumer.accept(e))
        );
    }

    /** Returns the active {@link PlanDashboardServer}, or null if not started. */
    public static PlanDashboardServer getDashboardServer() {
        return dashboardServer;
    }

    /** Called by {@code /steve dashboard} to set/clear the active server. */
    public static void setDashboardServer(PlanDashboardServer server) {
        dashboardServer = server;
    }
}

