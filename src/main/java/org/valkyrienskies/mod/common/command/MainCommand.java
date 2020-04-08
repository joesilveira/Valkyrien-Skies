package org.valkyrienskies.mod.common.command;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import org.valkyrienskies.mod.common.command.autocompleters.ShipNameAutocompleter;
import org.valkyrienskies.mod.common.entity.PhysicsWrapperEntity;
import org.valkyrienskies.mod.common.multithreaded.VSThread;
import org.valkyrienskies.mod.common.physmanagement.shipdata.QueryableShipData;
import org.valkyrienskies.mod.common.physmanagement.shipdata.ShipData;
import org.valkyrienskies.mod.common.ship_handling.IHasShipManager;
import org.valkyrienskies.mod.common.ship_handling.WorldServerShipManager;
import org.valkyrienskies.mod.common.tileentity.TileEntityPhysicsInfuser;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Model;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "valkyrienskies", aliases = "vs",
    synopsisSubcommandLabel = "COMMAND", mixinStandardHelpOptions = true,
    usageHelpWidth = 55,
    subcommands = {
        HelpCommand.class,
        MainCommand.ListShips.class,
        MainCommand.DisableShip.class,
        MainCommand.GC.class,
        MainCommand.TPS.class,
        MainCommand.KillRunaway.class})
public class MainCommand implements Runnable {

    @Spec
    private Model.CommandSpec spec;

    @Inject
    private ICommandSender sender;

    @Override
    public void run() {
        String usageMessage = spec.commandLine().getUsageMessage().replace("\r", "");

        sender.sendMessage(new TextComponentString(usageMessage));
    }

    @Command(name = "gc")
    static class GC implements Runnable {

        @Inject
        ICommandSender sender;

        public void run() {
            System.gc();
            sender.sendMessage(new TextComponentTranslation("commands.vs.gc.success"));
        }

    }

    @Command(name = "tps")
    static class TPS implements Runnable {

        @Inject
        ICommandSender sender;

        @Option(names = {"--world", "-w"})
        World world;

        @Override
        public void run() {
            if (world == null) {
                world = sender.getEntityWorld();
            }

            VSThread worldPhysicsThread = ((WorldServerShipManager) ((IHasShipManager) world)
                .getManager()).getPhysicsThread();

            if (worldPhysicsThread != null) {
                long averagePhysTickTimeNano = worldPhysicsThread.getAveragePhysicsTickTimeNano();
                double ticksPerSecond = 1000000000D / ((double) averagePhysTickTimeNano);
                double ticksPerSecondTwoDecimals = Math.floor(ticksPerSecond * 100) / 100;
                sender.sendMessage(new TextComponentString(
                    "Player world: " + ticksPerSecondTwoDecimals + " physics ticks per second"));
            }
        }
    }

    @Command(name = "example", aliases = "ex",
            synopsisSubcommandLabel = "COMMAND")
    public static class ExampleCommand implements Runnable {
        @Inject
        ICommandSender sender;

        @Parameters
        String message;

        @Option(names = "t")
        String target;

        @Override
        public void run() {
            if (target != null) {
                sender.getEntityWorld().getPlayerEntityByName(target)
                        .sendMessage(new TextComponentString(message));
            } else {
                sender.sendMessage(new TextComponentString(message));
            }
        }
    }

    @Command(name = "ship-physics")
    static class DisableShip implements Runnable {

        @Inject
        ICommandSender sender;

        @Spec
        CommandSpec spec;

        @Parameters(index = "0", completionCandidates = ShipNameAutocompleter.class)
        String shipName;

        @Parameters(index = "1", arity = "0..1")
        boolean enabled;

        @Override
        public void run() {
            World world = sender.getEntityWorld();
            QueryableShipData data = QueryableShipData.get(world);
            Optional<ShipData> oTargetShipData = data.getShipFromName(shipName);

            if (!oTargetShipData.isPresent()) {
                sender.sendMessage(new TextComponentString(
                    "That ship, " + shipName + " could not be found"));
                return;
            }

            ShipData targetShipData = oTargetShipData.get();
            Optional<Entity> oEntity = world.loadedEntityList.stream()
                .filter(e -> e.getPersistentID().equals(targetShipData.getUUID()))
                .findAny();

            if (!oEntity.isPresent()) {
                throw new RuntimeException("QueryableShipData is incorrect?");
            }

            try {
                PhysicsWrapperEntity wrapperEntity = (PhysicsWrapperEntity) oEntity.get();
                BlockPos infuserPos = wrapperEntity.getPhysicsObject().getPhysicsInfuserPos();
                TileEntityPhysicsInfuser infuser = Objects.requireNonNull(
                    (TileEntityPhysicsInfuser) world.getTileEntity(infuserPos));

                if (spec.commandLine().getParseResult().hasMatchedPositional(1)) {
                    infuser.setPhysicsEnabled(enabled);
                    sender.sendMessage(new TextComponentString(
                        "Successfully set the physics of ship " + shipName + " to " +
                            (infuser.isPhysicsEnabled() ? "enabled" : "disabled")
                    ));
                } else {
                    sender.sendMessage(new TextComponentString(
                        "The physics of the ship " + shipName + " is " +
                            (infuser.isPhysicsEnabled() ? "enabled" : "disabled")
                    ));
                }

            } catch (ClassCastException e) {
                throw new RuntimeException("Ship entity is not PhysicsWrapperEntity or "
                    + "Physics infuser is not a physics infuser?", e);
            }
        }
    }


    //Joe Silveira
    @Command(name = "list-ships", aliases = "ls")
    static class ListShips implements Runnable {

        @Inject
        ICommandSender sender;

        @Option(names = {"-v", "--verbose"})
        boolean verbose;

        @Override
        public void run() {

            World world = sender.getEntityWorld();
            QueryableShipData data = ValkyrienUtils.getQueryableData(world);

            if (data.getShips().size() == 0) {
                // There are no ships
                sender.sendMessage(new TextComponentTranslation("commands.vs.list-ships.noships"));
                return;
            }

            String listOfShips;

            if (verbose) {

                listOfShips = data.getShips()
                    .stream()
                    .map(shipData -> {

                        //if the ship position cant be found lets reload it or wipe it most likely network issue
                        if (shipData.getPositionData() == null) {
                            UUID missingShipId = shipData.getUUID();
                            if(data.getShip(missingShipId).isPresent()){
                                ShipData tempShip = new ShipData.Builder().setUUID(missingShipId).build();
                                tempShip.positionData = shipData.getPositionData();
                                shipData.DestroyShip();
                                return String.format("%s, Ship Reloading", shipData.getName());
                            }
                            return String.format("%s, Unknown Location", shipData.getName());
                        } else {
                            return String.format("%s [%.1f, %.1f, %.1f]", shipData.getName(),
                                shipData.getPositionData().getPosX(),
                                shipData.getPositionData().getPosY(),
                                shipData.getPositionData().getPosZ());
                        }
                    })
                    .collect(Collectors.joining(",\n"));
            } else {
                listOfShips = data.getShips()
                    .stream()
                    .map(ShipData::getName)
                    .collect(Collectors.joining(",\n"));
            }

            sender.sendMessage(new TextComponentTranslation(
                "commands.vs.list-ships.ships", listOfShips));

            sender.sendMessage(new TextComponentTranslation("commands.message.usage"));


        }

    }

    @Command(name = "kill-runaways", aliases = "kr")
    static class KillRunaway implements Runnable {

        @Inject
        ICommandSender sender;

        @Override
        public void run() {
            double posY;
            int currentShipIterator;
            World world = sender.getEntityWorld();
            QueryableShipData data = ValkyrienUtils.getQueryableData(world);
            List<ShipData> ships = data.getShips();
            if (ships.size() == 0) {
                // There are no ships
                sender.sendMessage(new TextComponentTranslation("commands.vs.list-ships.noships"));
                return;
            }else{
                for(currentShipIterator = 0; currentShipIterator < ships.size(); currentShipIterator++){
                    ShipData currentShip = ships.get(currentShipIterator);
                    assert currentShip.getPositionData() != null;
                    posY = currentShip.getPositionData().getPosY();
                    sender.sendMessage(new TextComponentTranslation(currentShip.getUUID().toString()));
                    //463-464 (different each time, but always between these two numbers) seems to be an upper limit
                    //on how high the Y variable goes for a ship in game (even though they can physically go higher
                    // then that. This might be a bug that gets fixed later? Unsure...
                    if((posY > 463 && posY < 464) || posY > 10000 || posY < -10000){
                        Optional<ShipData> oTargetShipData = data.getShip(currentShip.getUUID());
                        if(data.removeShip(currentShip.getUUID())){
                            if (!oTargetShipData.isPresent()) {
                                sender.sendMessage(new TextComponentTranslation("commands.vs.kill-runaway.failure", oTargetShipData.get().getUUID()));
                                return;
                            }
                            ShipData targetShipData = oTargetShipData.get();
                            Optional<Entity> oEntity = world.loadedEntityList.stream()
                                    .filter(e -> e.getPersistentID().equals(targetShipData.getUUID()))
                                    .findAny();
                            if (!oEntity.isPresent()) {
                                throw new RuntimeException("QueryableShipData is incorrect?");
                            }
                            try {
                                PhysicsWrapperEntity wrapperEntity = (PhysicsWrapperEntity) oEntity.get();
                                wrapperEntity.destroyPhysicsObject();
                            }catch (ClassCastException e) {
                                throw new RuntimeException("Ship entity is not PhysicsWrapperEntity or "
                                        + "Physics infuser is not a physics infuser?", e);
                            }
                            sender.sendMessage(new TextComponentTranslation("commands.vs.kill-runaway.success", currentShip.getUUID()));
                        }else{
                            sender.sendMessage(new TextComponentTranslation("commands.vs.kill-runaway.failure", currentShip.getUUID()));
                        }
                    }
                }
            }
            sender.sendMessage(new TextComponentTranslation("commands.vs.test"));
            return;
        }

    }

}
