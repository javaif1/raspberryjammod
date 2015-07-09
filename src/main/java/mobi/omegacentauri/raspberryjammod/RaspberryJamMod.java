package mobi.omegacentauri.raspberryjammod;

import ibxm.Player;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.InputMismatchException;
import java.util.Scanner;

import scala.collection.script.Script;

import net.minecraft.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.event.FMLStateEvent;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@Mod(modid = RaspberryJamMod.MODID, version = RaspberryJamMod.VERSION, name = RaspberryJamMod.NAME,
guiFactory = "mobi.omegacentauri.raspberryjammod.GuiFactory")
public class RaspberryJamMod
{
	public static final String MODID = "raspberryjammod";
	public static final String VERSION = "0.40";
	public static final String NAME = "Raspberry Jam Mod";
	private APIServer fullAPIServer = null;
	private PythonExternalCommand pythonExternalCommand = null;
	private NightVisionExternalCommand nightVisionExternalCommand = null;
	public static ScriptExternalCommand[] scriptExternalCommands = null;
	public static Configuration configFile;
	public static int portNumber = 4711;
	public static boolean concurrent = true;
	public static boolean leftClickToo = true;
	public static boolean allowRemote = true;
	public static boolean globalChatMessages = true;
	public static String pythonInterpreter = "python";
	public static boolean integrated = true;
	public static volatile boolean serverActive = false;
	private ClientEventHandler clientEventHandler = null;
	static boolean clientOnlyAPI = false;
	private MCEventHandler serverEventHandler = null;

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		integrated = true;
		try {
			Class.forName("net.minecraft.client.Minecraft");
		}
		catch (ClassNotFoundException e) {
			integrated = false;
		}

		configFile = new Configuration(event.getSuggestedConfigurationFile());
		configFile.load();
		System.out.println("configFile = "+configFile.getConfigFile().getPath());
//		KeyBindings.init();

		synchronizeConfig();
	}

	@Mod.EventHandler
	@SideOnly(Side.CLIENT)
	public void onConfigChanged(net.minecraftforge.fml.client.event.ConfigChangedEvent.OnConfigChangedEvent event) {
		System.out.println("config changed 0");
        }


	@Mod.EventHandler
	@SideOnly(Side.CLIENT)
	public void Init(FMLInitializationEvent event) {
		System.out.println("FMLInitializationEvent");
		clientEventHandler = new ClientEventHandler();
		MinecraftForge.EVENT_BUS.register(clientEventHandler);
		FMLCommonHandler.instance().bus().register(clientEventHandler);
		nightVisionExternalCommand = new NightVisionExternalCommand(clientEventHandler);
		net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(nightVisionExternalCommand);
	}

	public static void synchronizeConfig() {
		portNumber = configFile.getInt("Port Number", Configuration.CATEGORY_GENERAL, 4711, 0, 65535, "Port number");
		concurrent = configFile.getBoolean("Multiple Connections", Configuration.CATEGORY_GENERAL, true, "Multiple connections");
		allowRemote = configFile.getBoolean("Remote Connections", Configuration.CATEGORY_GENERAL, true, "Remote connections");
		leftClickToo = configFile.getBoolean("Detect Sword Left-Click", Configuration.CATEGORY_GENERAL, false, "Detect sword left-click");
		pythonInterpreter = configFile.getString("Python Interpreter", Configuration.CATEGORY_GENERAL, "python", "Python interpreter");
		globalChatMessages = configFile.getBoolean("Messages Go To All", Configuration.CATEGORY_GENERAL, true, "Messages go to all");
		clientOnlyAPI = configFile.getBoolean("Read-Only Client-Based API", Configuration.CATEGORY_GENERAL, false, "Read-only API");
//		clientOnlyPortNumber = configFile.getInt("Port Number for Client-Only API", Configuration.CATEGORY_GENERAL, 0, 0, 65535, "Client-only API port number (normally 0)");

		if (configFile.hasChanged())
			configFile.save();
	}

	public static int closeAllScripts() {
		if (scriptExternalCommands == null)
			return 0;
		int count = 0;
		for (ScriptExternalCommand c : scriptExternalCommands)
			count += c.close();
		return count;
	}
	
	@EventHandler
	public void onServerStopping(FMLServerStoppingEvent event) {
		serverActive = false;

		if (serverEventHandler != null) {
			FMLCommonHandler.instance().bus().unregister(serverEventHandler);
			serverEventHandler = null;
		}

		if (fullAPIServer != null) {
			fullAPIServer.close();
		}
		closeAllScripts();
		scriptExternalCommands = null;
	}
	
	@EventHandler
	public void onServerStarting(FMLServerStartingEvent event) {
		System.out.println("Server started event");
		
		synchronizeConfig();

		if (clientOnlyAPI)
			return;
			
		clientEventHandler.closeAPI();

		serverActive = true;
		
		serverEventHandler = new MCEventHandlerServer();
		FMLCommonHandler.instance().bus().register(serverEventHandler);
		MinecraftForge.EVENT_BUS.register(serverEventHandler);
		try {
			fullAPIServer = new APIServer(serverEventHandler, portNumber, false);

			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						fullAPIServer.communicate();
					} catch(IOException e) {
						System.out.println("RaspberryJamMod error "+e);
					}
					finally {
						System.out.println("Closing RaspberryJamMod");
						if (fullAPIServer != null)
							fullAPIServer.close();
					}
				}

			}).start();
		} catch (IOException e1) {
			System.out.println("Threw "+e1);
		}

		scriptExternalCommands = new ScriptExternalCommand[] {
				new PythonExternalCommand(false),
				new AddPythonExternalCommand(false)
		};
		for (ScriptExternalCommand c : scriptExternalCommands) {
			event.registerServerCommand(c);
		}
	}
}
