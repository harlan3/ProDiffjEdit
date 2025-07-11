/*
 * jEdit.java - Main class of the jEdit editor
 * :tabSize=4:indentSize=4:noTabs=false:
 * :folding=explicit:collapseFolds=1:
 *
 * Copyright (C) 1998, 2005 Slava Pestov
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.gjt.sp.jedit;

//{{{ Imports
import java.io.Closeable;
import java.io.IOException;

import org.gjt.sp.jedit.datatransfer.JEditTransferableService;
import org.gjt.sp.jedit.gui.tray.JTrayIconManager;
import org.gjt.sp.jedit.manager.*;
import org.gjt.sp.util.*;
import org.jedit.core.MigrationService;
import org.jedit.migration.OneTimeMigrationService;
import org.jedit.keymap.KeymapManager;
import org.jedit.keymap.KeymapManagerImpl;
import org.gjt.sp.jedit.visitors.JEditVisitor;

import java.awt.*;

import org.gjt.sp.jedit.View.ViewConfig;
import org.gjt.sp.jedit.bsh.UtilEvalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.swing.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.*;
import java.util.List;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.xml.sax.SAXParseException;

import org.gjt.sp.jedit.bufferio.BufferIORequest;
import org.gjt.sp.jedit.buffer.KillRing;
import org.gjt.sp.jedit.buffer.JEditBuffer;
import org.gjt.sp.jedit.buffer.FoldHandler;
import org.gjt.sp.jedit.msg.*;
import org.gjt.sp.jedit.gui.*;
import org.gjt.sp.jedit.help.HelpViewer;
import org.gjt.sp.jedit.io.*;
import org.gjt.sp.jedit.pluginmgr.PluginManager;
import org.gjt.sp.jedit.search.SearchAndReplace;
import org.gjt.sp.jedit.syntax.Chunk;
import org.gjt.sp.jedit.syntax.ModeProvider;
import org.gjt.sp.jedit.syntax.TokenMarker;
import org.gjt.sp.jedit.syntax.XModeHandler;
import org.gjt.sp.jedit.textarea.*;
import org.gjt.sp.jedit.visitors.SaveCaretInfoVisitor;
import org.gjt.sp.jedit.bufferset.BufferSetManager;
import org.gjt.sp.jedit.bufferset.BufferSet;

import static java.lang.Integer.parseInt;
//}}}

/**
 * The main class of the jEdit text editor.
 * @author Slava Pestov
 * @version $Id$
 */
public class jEdit implements Runnable
{
	
	private static View currentView = null;
	
	//{{{ getVersion() method
	/**
	 * Returns the jEdit version as a human-readable string.
	 */
	public static String getVersion()
	{
		return MiscUtilities.buildToVersion(getBuild());
	} //}}}

	//{{{ getBuild() method
	/**
	 * Returns the internal version. MiscUtilities.compareStrings() can be used
	 * to compare different internal versions.
	 */
	public static String getBuild()
	{
		// (major).(minor).(<99 = preX, 99 = "final").(bug fix)
		return "05.07.01.00";
	} //}}}

	//{{{ main() method
	/**
	 * The main method of the jEdit application.
	 * This should never be invoked directly.
	 * @param args The command line arguments
	 */
	//public static void main(String[] args)
	public static void mainApplicationStart(String[] args)
	{
		// doing a copy to log it later as original args array is modified
		String[] _args = args.clone();
		//{{{ Check for Java 11 or later
		String javaVersion = System.getProperty("java.version");
		String majorVersion = javaVersion.split("\\.", 2)[0];
		if (majorVersion.endsWith("-ea"))
		{
			majorVersion = majorVersion.substring(0, majorVersion.length() - 3);
		}
		int javaMajorVersion = parseInt(majorVersion);
		if(javaMajorVersion < 11)
		{
			System.err.println("You are running Java version "
				+ javaVersion + '.');
			System.err.println("jEdit requires Java 11 or later.");
			System.exit(1);
		} //}}}

		startupDone.add(false);

		// later on we need to know if certain code is called from
		// the main thread
		mainThread = Thread.currentThread();

		settingsDirectory = MiscUtilities.constructPath(
				System.getProperty("user.home"), ".jedit");
		// On mac, different rules (should) apply
		if(OperatingSystem.isMacOS())
			settingsDirectory = MiscUtilities.constructPath(
				System.getProperty("user.home"), "Library/jEdit" );
		else if (OperatingSystem.isWindows())
		{
			String appData = System.getenv("APPDATA");
			if (appData != null)
				settingsDirectory = MiscUtilities.constructPath(
					appData, "jEdit");
		}
		// MacOS users expect the app to keep running after all windows
		// are closed
		background = OperatingSystem.isMacOS();

		//{{{ Parse command line
		boolean endOpts = false;
		int level = Log.WARNING;
		String portFile = "server";
		boolean restore = true;
		boolean newView = true;
		boolean newPlainView = false;
		boolean gui = true; // open initial view?
		boolean loadPlugins = true;
		boolean runStartupScripts = true;
		boolean quit = false;
		boolean wait = false;
		boolean shouldRelocateSettings = true;
		String userDir = System.getProperty("user.dir");
		boolean splash = true;

		// script to run
		String scriptFile = null;

		for(int i = 0; i < args.length; i++)
		{
			String arg = args[i];
			if(arg == null)
			{
			}
			else if(arg.isEmpty())
				args[i] = null;
			else if(arg.startsWith("-") && !endOpts)
			{
				if(arg.equals("--"))
					endOpts = true;
				else if(arg.equals("-usage"))
				{
					version();
					System.err.println();
					usage();
					System.exit(1);
				}
				else if(arg.equals("-version"))
				{
					version();
					System.exit(1);
				}
				else if(arg.startsWith("-log="))
				{
					try
					{
						level = parseInt(arg.substring("-log=".length()));
					}
					catch(NumberFormatException nf)
					{
						System.err.println("Malformed option: " + arg);
					}
				}
				else if(arg.equals("-nosettings"))
					settingsDirectory = null;
				else if(arg.startsWith("-settings="))
				{
					settingsDirectory = arg.substring(10);
					shouldRelocateSettings = false;
				}
				else if(arg.startsWith("-noserver"))
					portFile = null;
				else if(arg.equals("-server"))
					portFile = "server";
				else if(arg.startsWith("-server="))
					portFile = arg.substring(8);
				else if(arg.startsWith("-background"))
					background = true;
				else if(arg.startsWith("-nobackground"))
					background = false;
				else if(arg.equals("-gui"))
					gui = true;
				else if(arg.equals("-nogui"))
					gui = false;
				else if(arg.equals("-newview"))
					newView = true;
				else if(arg.equals("-newplainview"))
					newPlainView = true;
				else if(arg.equals("-reuseview"))
					newPlainView = newView = false;
				else if(arg.equals("-restore"))
					restore = true;
				else if(arg.equals("-norestore"))
					restore = false;
				else if(arg.equals("-plugins"))
					loadPlugins = true;
				else if(arg.equals("-noplugins"))
					loadPlugins = false;
				else if(arg.equals("-startupscripts"))
					runStartupScripts = true;
				else if(arg.equals("-nostartupscripts"))
					runStartupScripts = false;
				else if(arg.startsWith("-run="))
					scriptFile = arg.substring(5);
				else if(arg.equals("-wait"))
					wait = true;
				else if(arg.equals("-quit"))
					quit = true;
				else if(arg.equals("-nosplash"))
					splash = false;
				else
				{
					System.err.println("Unknown option: "
						+ arg);
					usage();
					System.exit(1);
				}
				args[i] = null;
			}
		} //}}}

		JTrayIconManager.setTrayIconArgs(restore, userDir, args);


		//{{{ We need these initializations very early on
		if(settingsDirectory != null)
		{
			settingsDirectory = MiscUtilities.resolveSymlinks(
				settingsDirectory);
		}

		if(settingsDirectory != null && portFile != null)
			portFile = MiscUtilities.constructPath(settingsDirectory,portFile);
		else
			portFile = null;

		Log.init(true,level);

		Log.log(Log.MESSAGE,jEdit.class, "starting with command line arguments: " + String.join(" ", _args));
		//}}}

		//{{{ Try connecting to another running jEdit instance
		if(portFile != null && new File(portFile).exists())
		{
			BufferedReader in = null;
			DataOutputStream out = null;
			try
			{
				in = new BufferedReader(new FileReader(portFile));
				String check = in.readLine();
				if(!"b".equals(check))
					throw new IllegalArgumentException("Wrong port file format");

				int port = parseInt(in.readLine());
				int key = parseInt(in.readLine());

				// socket is closed via BeanShell script below
				@SuppressWarnings("resource")
				Socket socket = new Socket(InetAddress.getByName(null),port);
				out = new DataOutputStream(socket.getOutputStream());
				out.writeInt(key);

				String script;
				if(quit)
				{
					script = "socket.close();\n"
						+ "jEdit.exit(null,true);\n";
				}
				else
				{
					script = makeServerScript(wait,restore,
						newView,newPlainView,args,
						scriptFile);
				}

				out.writeUTF(script);

				Log.log(Log.DEBUG,jEdit.class,"Waiting for server");

				// block until its closed
				socket.getInputStream().read();

				System.exit(0);
			}
			catch(Exception e)
			{
				// ok, this one seems to confuse newbies
				// endlessly, so log it as NOTICE, not
				// ERROR
				Log.log(Log.NOTICE,jEdit.class,"An error occurred"
					+ " while connecting to the jEdit server instance.");
				Log.log(Log.NOTICE,jEdit.class,"This probably means that"
					+ " jEdit crashed and/or exited abnormally");
				Log.log(Log.NOTICE,jEdit.class,"the last time it was run.");
				Log.log(Log.NOTICE,jEdit.class,"If you don't"
					+ " know what this means, don't worry.");
				Log.log(Log.NOTICE,jEdit.class,e);
			}
			finally
			{
				IOUtilities.closeQuietly(in);
				IOUtilities.closeQuietly(out);
			}
		}

		if(quit)
		{
			// if no server running and user runs jedit -quit,
			// just exit
			System.exit(0);
		} //}}}

		// don't show splash screen if there is a file named
		// 'nosplash' in the settings directory
		logTime("before splash screen activation");
		if(splash && (!new File(settingsDirectory,"nosplash").exists()))
			GUIUtilities.showSplashScreen();
		logTime("after splash screen activation");
		//{{{ Settings migration code.
		// Windows check introduced in 5.0pre1.
		// MacOS check introduced in 4.3.
		if((OperatingSystem.isMacOS() || OperatingSystem.isWindows())
			&& shouldRelocateSettings && settingsDirectory != null)
		{
			relocateSettings();
		}
		// }}}

		//{{{ Initialize settings directory
		Writer stream;
		if(settingsDirectory != null)
		{
			File _settingsDirectory = new File(settingsDirectory);
			if(!_settingsDirectory.exists())
				_settingsDirectory.mkdirs();
			File _macrosDirectory = new File(settingsDirectory,"macros");
			if(!_macrosDirectory.exists())
				_macrosDirectory.mkdir();

			String logPath = MiscUtilities.constructPath(
				settingsDirectory,"activity.log");

			backupSettingsFile(new File(logPath));

			try
			{
				stream = new BufferedWriter(new FileWriter(logPath));

				// Write a warning message:
				String lineSep = System.getProperty("line.separator");
				stream.write("Log file created on " + new Date());
				stream.write(lineSep);
				stream.write("IMPORTANT:");
				stream.write(lineSep);
				stream.write("Because updating this file after "
					+ "every log message would kill");
				stream.write(lineSep);
				stream.write("performance, it will be *incomplete* "
					+ "unless you invoke the");
				stream.write(lineSep);
				stream.write("Utilities->Troubleshooting->Update "
					+ "Activity Log on Disk command!");
				stream.write(lineSep);
			}
			catch(Exception e)
			{
				e.printStackTrace();
				stream = null;
			}
		}
		else
		{
			stream = null;
		} //}}}
		Log.setLogWriter(stream);

		Log.log(Log.NOTICE,jEdit.class,"jEdit version " + getVersion());
		Log.log(Log.MESSAGE,jEdit.class,"Settings directory is "
			+ settingsDirectory);

		//{{{ Get things rolling
		GUIUtilities.advanceSplashProgress("init");
		initMisc();
		GUIUtilities.advanceSplashProgress("init system properties");
		initSystemProperties();

		GUIUtilities.advanceSplashProgress("init beanshell");
		BeanShell.init();

		GUIUtilities.advanceSplashProgress("loading site properties");
		if(jEditHome != null)
			initSiteProperties();

		GUIUtilities.advanceSplashProgress("loading user properties");
		initUserProperties();
		initLocalizationProperties(false);

		GUIUtilities.advanceSplashProgress("init GUI");
		GUIUtilities.init();

		bufferSetManager = new BufferSetManager();
		//}}}

		//{{{ Initialize server
		if(portFile != null)
		{
			GUIUtilities.advanceSplashProgress("init server");
			server = new EditServer(portFile);
			if(!server.isOK())
				server = null;
		}
		else
		{
			GUIUtilities.advanceSplashProgress();
			if(background)
			{
				background = false;
				Log.log(Log.WARNING,jEdit.class,"You cannot specify both the"
					+ " -background and -noserver switches");
			}
		} //}}}

		//{{{ Do more stuff
		GUIUtilities.advanceSplashProgress("init look and feel");
		initPLAF();
		GUIUtilities.advanceSplashProgress("init VFS Manager");
		VFSManager.init();
		GUIUtilities.advanceSplashProgress("init resources");
		initResources();

		if (settingsDirectory != null)
		{
			GUIUtilities.advanceSplashProgress("Migrate keymaps");
			MigrationService keymapMigration = ServiceManager.getService(MigrationService.class, "keymap");
			keymapMigration.migrate();
		}
		else
			GUIUtilities.advanceSplashProgress();

		SearchAndReplace.load();

		if(loadPlugins)
		{
			GUIUtilities.advanceSplashProgress("init plugins");
			initPlugins();
		}
		else
			GUIUtilities.advanceSplashProgress();

		Registers.setSaver(new JEditRegisterSaver());
		Registers.setListener(new JEditRegistersListener());
		GUIUtilities.advanceSplashProgress("init history model");
		HistoryModel.setSaver(new JEditHistoryModelSaver());
		HistoryModel.loadHistory();
		GUIUtilities.advanceSplashProgress("init buffer history");
		BufferHistory.load();
		GUIUtilities.advanceSplashProgress("init killring");
		KillRing.setInstance(new JEditKillRing());
		KillRing.getInstance().load();
		GUIUtilities.advanceSplashProgress("init various properties");

		// other one-time migration services.
		OneTimeMigrationService.execute();

		propertiesChanged();

		GUIUtilities.advanceSplashProgress("init modes");

		// Buffer sort
		bufferManager.setSortBuffers(getBooleanProperty("sortBuffers"));
		bufferManager.setSortByName(getBooleanProperty("sortByName"));

		reloadModes();

		GUIUtilities.advanceSplashProgress("activate plugins");
		//}}}

		//{{{ Activate plugins that must be activated at startup
		for(int i = 0; i < jars.size(); i++)
		{
			jars.elementAt(i).activatePluginIfNecessary();
		} //}}}

		String[] serviceNames = ServiceManager.getServiceNames(JEditTransferableService.class);
		for (String serviceName : serviceNames)
		{
			JEditTransferableService service = ServiceManager.getService(JEditTransferableService.class, serviceName);
			org.gjt.sp.jedit.datatransfer.TransferHandler.getInstance().registerTransferableService(service);
		}

		//{{{ Load macros and run startup scripts, after plugins and settings are loaded
		GUIUtilities.advanceSplashProgress("init macros");
		Macros.loadMacros();
		Macros.getMacroActionSet().initKeyBindings();

		if(runStartupScripts && jEditHome != null)
		{
			String path = MiscUtilities.constructPath(jEditHome,"startup");
			File file = new File(path);
			if(file.exists())
			{
				GUIUtilities.advanceSplashProgress("run startup scripts");
				runStartupScripts(file);
			}
			else
				GUIUtilities.advanceSplashProgress();
		}
		else
			GUIUtilities.advanceSplashProgress();

		if(runStartupScripts && settingsDirectory != null)
		{
			String path = MiscUtilities.constructPath(settingsDirectory,"startup");
			File file = new File(path);
			if (file.exists())
			{
				GUIUtilities.advanceSplashProgress("run startup scripts");
				runStartupScripts(file);
			}
			else
			{
				GUIUtilities.advanceSplashProgress();
				file.mkdirs();
			}
		}
		else
		{
			GUIUtilities.advanceSplashProgress();
		} //}}}

		//{{{ Run script specified with -run= parameter
		if(scriptFile != null)
		{
			GUIUtilities.advanceSplashProgress("run script file");
			scriptFile = MiscUtilities.constructPath(userDir,scriptFile);
			try
			{
				BeanShell.getNameSpace().setVariable("args",args);
			}
			catch(UtilEvalError e)
			{
				Log.log(Log.ERROR,jEdit.class,e);
			}
			BeanShell.runScript(null,scriptFile,null,false);
		}
		else
		{
			GUIUtilities.advanceSplashProgress();
		}
		//}}}

		GUIUtilities.advanceSplashProgress();

		// Create dynamic actions for switching to saved layouts.
		// The list of saved layouts is retrieved from the docking framework,
		// which can be provided by a plugin, so this must be called only after
		// the plugins are loaded.
		DockingLayoutManager.init();

		// Open files, create the view and hide the splash screen.
		SyntaxUtilities.propertyManager = jEdit.propertyManager;
		finishStartup(gui,restore,newPlainView,userDir,args);
		logTime("main done");
		
	} //}}}

	//{{{ Property methods

	//{{{ getCurrentLanguage() method
	/**
	 * Returns the current language used by jEdit.
	 *
	 * @return the current language, never null
	 * @since jEdit 5.0pre1
	 */
	public static String getCurrentLanguage()
	{
		String language;
		if (getBooleanProperty("lang.usedefaultlocale"))
		{
			language = Locale.getDefault().getLanguage();
		}
		else
		{
			language = getProperty("lang.current", "en");
		}
		return language;
	} //}}}

	//{{{ getProperties() method
	/**
	 * Returns the properties object which contains all known
	 * jEdit properties. Note that as of jEdit 4.2pre10, this returns a
	 * new collection, not the existing properties instance.
	 * @since jEdit 3.1pre4
	 */
	public static Properties getProperties()
	{
		return propMgr.getProperties();
	} //}}}

	//{{{ getProperty() method
	/**
	 * Fetches a property, returning null if it's not defined.
	 * @param name The property
	 */
	public static String getProperty(String name)
	{
		return propMgr.getProperty(name);
	} //}}}

	//{{{ getProperty() method
	/**
	 * Fetches a property, returning the default value if it's not
	 * defined.
	 * @param name The property
	 * @param def The default value
	 */
	public static String getProperty(String name, String def)
	{
		String value = propMgr.getProperty(name);
		if(value == null)
			return def;
		else
			return value;
	} //}}}

	//{{{ getProperty() method
	/**
	 * Returns the property with the specified name.<p>
	 *
	 * The elements of the <code>args</code> array are substituted
	 * into the value of the property in place of strings of the
	 * form <code>{<i>n</i>}</code>, where <code><i>n</i></code> is an index
	 * in the array.<p>
	 *
	 * You can find out more about this feature by reading the
	 * documentation for the <code>format</code> method of the
	 * <code>java.text.MessageFormat</code> class.
	 *
	 * @param name The property
	 * @param args The positional parameters
	 */
	public static String getProperty(String name, Object[] args)
	{
		if(name == null)
			return null;
		if(args == null)
			return getProperty(name);
		else
		{
			String value = getProperty(name);
			if(value == null)
				return null;
			else
				return MessageFormat.format(value,args);
		}
	} //}}}

	//{{{ getBooleanProperty() method
	/**
	 * Returns the value of a boolean property.
	 * @param name The property
	 */
	public static boolean getBooleanProperty(String name)
	{
		return getBooleanProperty(name,false);
	} //}}}

	//{{{ getBooleanProperty() method
	/**
	 * Returns the value of a boolean property.
	 * @param name The property
	 * @param def The default value
	 */
	public static boolean getBooleanProperty(String name, boolean def)
	{
		String value = getProperty(name);
		return StandardUtilities.getBoolean(value, def);
	} //}}}

	//{{{ getIntegerProperty() method
	/**
	 * Returns the value of an integer property.
	 * @param name The property
	 */
	public static int getIntegerProperty(String name)
	{
		return getIntegerProperty(name,0);
	} //}}}

	//{{{ getIntegerProperty() method
	/**
	 * Returns the value of an integer property.
	 * @param name The property
	 * @param def The default value
	 * @since jEdit 4.0pre1
	 */
	public static int getIntegerProperty(String name, int def)
	{
		String value = getProperty(name);
		if(value == null)
			return def;
		else
		{
			try
			{
				return parseInt(value.trim());
			}
			catch(NumberFormatException nf)
			{
				return def;
			}
		}
	} //}}}

	//{{{ getDoubleProperty() method
	public static double getDoubleProperty(String name, double def)
	{
		String value = getProperty(name);
		if(value == null)
			return def;
		else
		{
			try
			{
				return Double.parseDouble(value.trim());
			}
			catch(NumberFormatException nf)
			{
				return def;
			}
		}
	}
	//}}}

	//{{{ getFontProperty() method
	/**
	 * Returns the value of a font property. The family is stored
	 * in the <code><i>name</i></code> property, the font size is stored
	 * in the <code><i>name</i>size</code> property, and the font style is
	 * stored in <code><i>name</i>style</code>. For example, if
	 * <code><i>name</i></code> is <code>view.gutter.font</code>, the
	 * properties will be named <code>view.gutter.font</code>,
	 * <code>view.gutter.fontsize</code>, and
	 * <code>view.gutter.fontstyle</code>.
	 *
	 * @param name The property
	 * @since jEdit 4.0pre1
	 */
	public static Font getFontProperty(String name)
	{
		return getFontProperty(name,null);
	} //}}}

	//{{{ getFontProperty() method
	/**
	 * Returns the value of a font property. The family is stored
	 * in the <code><i>name</i></code> property, the font size is stored
	 * in the <code><i>name</i>size</code> property, and the font style is
	 * stored in <code><i>name</i>style</code>. For example, if
	 * <code><i>name</i></code> is <code>view.gutter.font</code>, the
	 * properties will be named <code>view.gutter.font</code>,
	 * <code>view.gutter.fontsize</code>, and
	 * <code>view.gutter.fontstyle</code>.
	 *
	 * @param name The property
	 * @param def The default value
	 * @since jEdit 4.0pre1
	 */
	public static Font getFontProperty(String name, Font def)
	{
		String family = getProperty(name);
		String sizeString = getProperty(name + "size");
		String styleString = getProperty(name + "style");

		if(family == null || sizeString == null || styleString == null)
			return def;
		else
		{
			int size, style;

			try
			{
				size = parseInt(sizeString);
			}
			catch(NumberFormatException nf)
			{
				return def;
			}

			try
			{
				style = parseInt(styleString);
			}
			catch(NumberFormatException nf)
			{
				return def;
			}

			return new Font(family,style,size);
		}
	} //}}}

	//{{{ getColorProperty() method
	/**
	 * Returns the value of a color property.
	 * @param name The property name
	 * @since jEdit 4.0pre1
	 */
	public static Color getColorProperty(String name)
	{
		return getColorProperty(name,Color.black);
	}

	/**
	 * Returns the value of a color property.
	 * @param name The property name
	 * @param def The default value
	 * @since jEdit 4.0pre1
	 */
	public static Color getColorProperty(String name, Color def)
	{
		String value = getProperty(name);
		if(value == null)
			return def;
		else
			return SyntaxUtilities.parseColor(value, def);
	} //}}}

	//{{{ setColorProperty() method
	/**
	 * Sets the value of a color property.
	 * @param name The property name
	 * @param value The value
	 * @since jEdit 4.0pre1
	 */
	public static void setColorProperty(String name, Color value)
	{
		setProperty(name, SyntaxUtilities.getColorHexString(value));
	} //}}}

	//{{{ getColorMatrixProperty() method
	/**
	 * Returns the value of a color matrix property.
	 * @param name The property name
	 * @since jEdit 5.6
	 */
	public static Color[][] getColorMatrixProperty(String name)
	{
		return getColorMatrixProperty(name, null);
	}

	/**
	 * Returns the value of a color matrix property.
	 * @param name The property name
	 * @param def The default value
	 * @since jEdit 5.6
	 */
	public static Color[][] getColorMatrixProperty(String name, Color[][] def)
	{
		String value = getProperty(name);
		if(value == null)
			return def;
		else
			return SyntaxUtilities.parseColorMatrix(value, def);
	} //}}}

	//{{{ setColorMatrixProperty() method
	/**
	 * Sets the value of a color matrix property.
	 * @param name The property name
	 * @param value The value
	 * @since jEdit 5.6
	 */
	public static void setColorMatrixProperty(String name, Color[][] value)
	{
		setProperty(name, SyntaxUtilities.getColorMatrixString(value));
	} //}}}

	//{{{ setProperty() method
	/**
	 * Sets a property to a new value.
	 * @param name The property
	 * @param value The new value
	 */
	public static void setProperty(String name, String value)
	{
		propMgr.setProperty(name,value);
	} //}}}

	//{{{ setTemporaryProperty() method
	/**
	 * Sets a property to a new value. Properties set using this
	 * method are not saved to the user properties list.
	 * @param name The property
	 * @param value The new value
	 * @since jEdit 2.3final
	 */
	public static void setTemporaryProperty(String name, String value)
	{
		propMgr.setTemporaryProperty(name,value);
	} //}}}

	//{{{ setBooleanProperty() method
	/**
	 * Sets a boolean property.
	 * @param name The property
	 * @param value The value
	 */
	public static void setBooleanProperty(String name, boolean value)
	{
		setProperty(name,value ? "true" : "false");
	} //}}}

	//{{{ setIntegerProperty() method
	/**
	 * Sets the value of an integer property.
	 * @param name The property
	 * @param value The value
	 * @since jEdit 4.0pre1
	 */
	public static void setIntegerProperty(String name, int value)
	{
		setProperty(name,String.valueOf(value));
	} //}}}

	//{{{ setDoubleProperty() method
	public static void setDoubleProperty(String name, double value)
	{
		setProperty(name,String.valueOf(value));
	}
	//}}}

	//{{{ setFontProperty() method
	/**
	 * Sets the value of a font property. The family is stored
	 * in the <code><i>name</i></code> property, the font size is stored
	 * in the <code><i>name</i>size</code> property, and the font style is
	 * stored in <code><i>name</i>style</code>. For example, if
	 * <code><i>name</i></code> is <code>view.gutter.font</code>, the
	 * properties will be named <code>view.gutter.font</code>,
	 * <code>view.gutter.fontsize</code>, and
	 * <code>view.gutter.fontstyle</code>.
	 *
	 * @param name The property
	 * @param value The value
	 * @since jEdit 4.0pre1
	 */
	public static void setFontProperty(String name, Font value)
	{
		setProperty(name,value.getFamily());
		setIntegerProperty(name + "size",value.getSize());
		setIntegerProperty(name + "style",value.getStyle());
	} //}}}

	//{{{ unsetProperty() method
	/**
	 * Unsets (clears) a property.
	 * @param name The property
	 */
	public static void unsetProperty(String name)
	{
		propMgr.unsetProperty(name);
	} //}}}

	//{{{ resetProperty() method
	/**
	 * Resets a property to its default value.
	 * @param name The property
	 *
	 * @since jEdit 2.5pre3
	 */
	public static void resetProperty(String name)
	{
		propMgr.resetProperty(name);
	} //}}}

	//{{{ propertiesChanged() method
	/**
	 * Reloads various settings from the properties.
	 */
	public static void propertiesChanged()
	{
		initLocalizationProperties(true);
		initPLAF();

		keymapManager.reload();
		initKeyBindings();

		Autosave.setInterval(getIntegerProperty("autosave",30));

		saveCaret = getBooleanProperty("saveCaret");

		UIDefaults defaults = UIManager.getDefaults();
		defaults.put("SplitPane.continuousLayout", true);

		// give all text areas the same font
		Font font = getFontProperty("view.font");

		//defaults.put("TextField.font",font);
		defaults.put("TextArea.font",font);
		defaults.put("TextPane.font",font);

		// Enable/Disable tooltips
		ToolTipManager.sharedInstance().setEnabled(
			jEdit.getBooleanProperty("showTooltips"));

		initProxy();

		// we do this here instead of adding buffers to the bus.
		bufferManager.forEach(buffer ->
		{
			buffer.resetCachedProperties();
			buffer.propertiesChanged();
		});

		HistoryModel.setDefaultMax(getIntegerProperty("history",25));
		HistoryModel.setDefaultMaxSize(getIntegerProperty("historyMaxSize", 5000000));
		KillRing.getInstance().propertiesChanged(getIntegerProperty("history",25));
		Chunk.propertiesChanged(propertyManager);
		Log.setBeepOnOutput(jEdit.getBooleanProperty("debug.beepOnOutput"));

		if (getBooleanProperty("systrayicon"))
		{
			EventQueue.invokeLater(JTrayIconManager::addTrayIcon);
		}
		else
		{
			JTrayIconManager.removeTrayIcon();
		}
		EditBus.send(new PropertiesChanged(null));
	} //}}}

	//}}} Property methods fold end

	//{{{ Plugin management methods

	//{{{ getNotLoadedPluginJARs() method
	/**
	 * Returns a list of plugin JARs pathnames that are not currently loaded
	 * by examining the user and system plugin directories.
	 * @since jEdit 3.2pre1
	 */
	public static String[] getNotLoadedPluginJARs()
	{
		Collection<String> returnValue = new ArrayList<>();

		if(jEditHome != null)
		{
			String systemPluginDir = MiscUtilities
				.constructPath(jEditHome,"jars");

			String[] list = new File(systemPluginDir).list();
			if(list != null)
				getNotLoadedPluginJARs(returnValue,systemPluginDir,list);
		}

		if(settingsDirectory != null)
		{
			String userPluginDir = MiscUtilities
				.constructPath(settingsDirectory,"jars");
			String[] list = new File(userPluginDir).list();
			if(list != null)
			{
				getNotLoadedPluginJARs(returnValue,
					userPluginDir,list);
			}
		}

		String[] _returnValue = returnValue.toArray(StandardUtilities.EMPTY_STRING_ARRAY);
		return _returnValue;
	} //}}}

	//{{{ getPlugin() method
	/**
	 * Returns the plugin with the specified class name.
	 * Only works for plugins that were loaded.
	 */
	public static EditPlugin getPlugin(String name)
	{
		return getPlugin(name, false);
	} //}}}

	//{{{ getPlugin(String, boolean) method
	/**
	 * Returns the plugin with the specified class name.
	 * If * <code>loadIfNecessary</code> is true, the plugin will be searched for,
	 * loaded, and activated in case it has not yet been loaded.
	 *
	 * @param name the classname of the main Plugin class.
	 * @param loadIfNecessary - loads plugin + dependencies if it is not loaded yet.
	 * @since jEdit 4.2pre4
	 */
	public static EditPlugin getPlugin(String name, boolean loadIfNecessary)
	{
		if (name == null)
		{
			return null;
		}

		EditPlugin[] plugins = getPlugins();
		EditPlugin plugin = null;
		for (EditPlugin ep : plugins)
		{
			if (ep.getClassName().equals(name))
			{
				plugin = ep;
				break;
			}
		}
		if (!loadIfNecessary)
		{
			return plugin;
		}
		if (plugin instanceof EditPlugin.Deferred)
		{
			plugin.getPluginJAR().activatePlugin();
			plugin = plugin.getPluginJAR().getPlugin();
		}
		String jarPath = PluginJAR.findPlugin(name);
		PluginJAR pjar = PluginJAR.load(jarPath, true);
		return pjar.getPlugin();
	} //}}}

	//{{{ getPlugins() method
	/**
	 * Returns an array of installed plugins.
	 */
	public static EditPlugin[] getPlugins()
	{
		Collection<EditPlugin> pluginList = new ArrayList<EditPlugin>();
		for(int i = 0; i < jars.size(); i++)
		{
			EditPlugin plugin = jars.elementAt(i).getPlugin();
			if(plugin != null)
				pluginList.add(plugin);
		}

		EditPlugin[] array = new EditPlugin[pluginList.size()];
		pluginList.toArray(array);
		return array;
	} //}}}

	//{{{ getPluginJARs() method
	/**
	 * Returns an array of installed plugins.
	 * @since jEdit 4.2pre1
	 */
	public static PluginJAR[] getPluginJARs()
	{
		PluginJAR[] array = new PluginJAR[jars.size()];
		jars.copyInto(array);
		return array;
	} //}}}

	//{{{ getPluginJAR() method
	/**
	 * Returns the JAR with the specified path name.
	 * @param path The path name
	 * @since jEdit 4.2pre1
	 */
	public static PluginJAR getPluginJAR(String path)
	{
		for(int i = 0; i < jars.size(); i++)
		{
			PluginJAR jar = jars.elementAt(i);
			if(jar.getPath().equals(path))
				return jar;
		}

		return null;
	} //}}}

	//{{{ addPluginJAR() method
	/**
	 * Loads the plugin JAR with the specified path. Some notes about this
	 * method:
	 *
	 * <ul>
	 * <li>Calling this at a time other than jEdit startup can have
	 * unpredictable results if the plugin has not been updated for the
	 * jEdit 4.2 plugin API.
	 * <li>You must make sure yourself the plugin is not already loaded.
	 * <li>After loading, you just make sure all the plugin's dependencies
	 * are satisified before activating the plugin, using the
	 * {@link PluginJAR#checkDependencies()} method.
	 * </ul>
	 *
	 * @param path The JAR file path
	 * @since jEdit 4.2pre1
	 */
	public static void addPluginJAR(String path)
	{
		PluginJAR jar = new PluginJAR(new File(path));
		jars.addElement(jar);
		if (jar.init())
		{
			String jarName = MiscUtilities.getFileName(path);
			jEdit.unsetProperty("plugin-blacklist."+jarName);
			jEdit.unsetProperty("plugin." + jarName + ".disabled");
			EditBus.send(new PluginUpdate(jar,PluginUpdate.LOADED,false));
			if(!isMainThread())
			{
				EditBus.send(new DynamicMenuChanged("plugins"));
				initKeyBindings();
			}
		}
		else
		{
			jars.removeElement(jar);
			jar.uninit(false);
		}
	} //}}}

	//{{{ addPluginJARsFromDirectory() method
	/**
	 * Loads all plugins in a directory.
	 * @param directory The directory
	 * @since jEdit 4.2pre1
	 */
	private static void addPluginJARsFromDirectory(String directory)
	{
		Log.log(Log.NOTICE,jEdit.class,"Loading plugins from "
			+ directory);

		File file = new File(directory);
		if(!(file.exists() && file.isDirectory()))
			return;
		String[] plugins = file.list();
		if(plugins == null)
			return;

		for (String plugin : plugins)
		{
			if (!plugin.toLowerCase().endsWith(".jar"))
				continue;

			String path = MiscUtilities.constructPath(directory, plugin);
			if (jEdit.getBooleanProperty("plugin-blacklist." + plugin))
				continue;

			addPluginJAR(path);
		}
	} //}}}

	//{{{ removePluginJAR() method
	/**
	 * Unloads the given plugin JAR with the specified path. Note that
	 * calling this at a time other than jEdit shutdown can have
	 * unpredictable results if the plugin has not been updated for the
	 * jEdit 4.2 plugin API.
	 *
	 * @param jar The <code>PluginJAR</code> instance
	 * @param exit Set to true if jEdit is exiting; enables some
	 * shortcuts so the editor can close faster.
	 * @since jEdit 4.2pre1
	 */
	public static void removePluginJAR(PluginJAR jar, boolean exit)
	{
		if(exit)
		{
			jar.uninit(true);
		}
		else
		{
			jar.uninit(false);
			jars.removeElement(jar);
			if (!isMainThread())
				initKeyBindings();
		}

		EditBus.send(new PluginUpdate(jar,PluginUpdate.UNLOADED,exit));
		if(!isMainThread() && !exit)
			EditBus.send(new DynamicMenuChanged("plugins"));
	} //}}}

	//}}}

	//{{{ Action methods

	//{{{ getActionContext() method
	/**
	 * Returns the action context used to store editor actions.
	 * @since jEdit 4.2pre1
	 */
	public static ActionContext getActionContext()
	{
		return actionContext;
	} //}}}

	//{{{ addActionSet() method
	/**
	 * Adds a new action set to jEdit's list of ActionSets (viewable from the shortcuts
	 * option pane). By default, each plugin has one ActionSet,
	 * but some plugins may create dynamic action sets, such as ProjectViewer and Console.
	 * These plugins must call removeActionSet() when the plugin is unloaded.
	 *
	 * @since jEdit 4.0pre1
	 * @see #removeActionSet(ActionSet)
	 */
	public static void addActionSet(ActionSet actionSet)
	{
		actionContext.addActionSet(actionSet);
	} //}}}

	//{{{ removeActionSet() method
	/**
	 * Removes an action set from jEdit's list.
	 * Plugins that add a dynamic action set must call this method at plugin
	 * unload time.
	 * @since jEdit 4.2pre1
	 */
	public static void removeActionSet(ActionSet actionSet)
	{
		actionContext.removeActionSet(actionSet);
	} //}}}

	//{{{ getBuiltInActionSet() method
	/**
	 * Returns the set of commands built into jEdit.
	 * @since jEdit 4.2pre1
	 */
	public static ActionSet getBuiltInActionSet()
	{
		return builtInActionSet;
	} //}}}

	// {{{ getActionSets() method
	/**
	 * Returns all registered action sets.
	 *
	 * @return the ActionSet(s)
	 * @since jEdit 4.0pre1
	 */
	public static ActionSet[] getActionSets()
	{
		return actionContext.getActionSets();
	} // }}}

	//{{{ getAction() method
	/**
	 * Returns the specified action.
	 * @param name The action name
	 */
	public static EditAction getAction(String name)
	{
		return actionContext.getAction(name);
	} //}}}

	//{{{ getActionSetForAction() method
	/**
	 * Returns the action set that contains the specified action.
	 *
	 * @param action The action
	 * @since jEdit 4.2pre1
	 */
	public static ActionSet getActionSetForAction(String action)
	{
		return actionContext.getActionSetForAction(action);
	} //}}}

	//{{{ getActionNames() method
	/**
	 * Returns all registered action names.
	 */
	public static String[] getActionNames()
	{
		return actionContext.getActionNames();
	} //}}}

	//}}}

	//{{{ Edit mode methods

	//{{{ reloadModes() method
	/**
	 * Reloads all edit modes.  User defined edit modes are loaded after
	 * global modes so that user modes supercede global modes.
	 * @since jEdit 3.2pre2
	 */
	public static void reloadModes()
	{
		ModeProvider.instance.removeAll();

		//{{{ Load the global catalog first
		if(jEditHome == null)
			loadModeCatalog("/modes/catalog", true, false);
		else
		{
			loadModeCatalog(MiscUtilities.constructPath(jEditHome,
				"modes","catalog"), false, false);
		} //}}}

		//Load user catalog second so user modes override global modes.
		loadUserModeCatalog();

		// This reloads the token marker and sends a message
		// which causes edit panes to repaint their text areas
		bufferManager.getBuffers().forEach(Buffer::setMode);
	} //}}}

	//{{{ loadUserModeCatalog() method
	private static void loadUserModeCatalog()
	{
		if (settingsDirectory == null)
			return;

		Log.log(Log.DEBUG, jEdit.class, "Loading user mode catalog");
		Path userModeDir = Path.of(MiscUtilities.constructPath(settingsDirectory,"modes"));
		if(Files.notExists(userModeDir))
		{
			Log.log(Log.DEBUG, jEdit.class, "The user mode path doesn't exist, creating it");
			try
			{
				Files.createDirectories(userModeDir);
			}
			catch (IOException e)
			{
				Log.log(Log.DEBUG, jEdit.class, e, e);
				return;
			}
		}

		Path userCatalog = Path.of(MiscUtilities.constructPath(settingsDirectory,"modes","catalog"));
		if (Files.notExists(userCatalog))
		{
			try
			{
				// create dummy catalog
				Files.writeString(userCatalog, jEdit.getProperty("defaultCatalog"));
			}
			catch (IOException io)
			{
				Log.log(Log.ERROR,jEdit.class,io);
			}
		}
		else
			loadModeCatalog(userCatalog.toString(), false, true);
	} //}}}

	//{{{ getMode() method
	/**
	 * Returns the edit mode with the specified name.
	 * @param name The edit mode
	 */
	public static Mode getMode(String name)
	{
		return ModeProvider.instance.getMode(name);
	} //}}}

	//{{{ getModes() method
	/**
	 * @return an array of installed edit modes that have been selected in the
	 * global options. The modes in this array will be sorted by mode name.
	 */
	public static Mode[] getModes()
	{
		Mode[] modes = ModeProvider.instance.getModes();
		Set<Mode> selected = new HashSet<>();
		for (Mode mode : modes)
		{
			if (!jEdit.getBooleanProperty("mode.opt-out." + mode.getName(), false))
			{
				selected.add(mode);
			}
		}
		modes = selected.toArray(new Mode[0]);
		Arrays.sort(modes, new StandardUtilities.StringCompare<>(true));
		return modes;
	} //}}}

	//{{{ getAllModes() method
	/**
	 * Returns an array of all installed edit modes. The modes in this array
	 * will be sorted by mode name.
	 */
	public static Mode[] getAllModes()
	{
		Mode[] modes = ModeProvider.instance.getModes();
		Arrays.sort(modes, new StandardUtilities.StringCompare<>(true));
		return modes;
	} //}}}

	//}}}

	//{{{ Buffer creation methods

	//{{{ openFiles() method
	/**
	 * Opens the file names specified in the argument array. This
	 * handles +line and +marker arguments just like the command
	 * line parser.
	 * @param parent The parent directory
	 * @param args The file names to open
	 * @since jEdit 3.2pre4
	 */
	public static Buffer openFiles(View view, String parent, String[] args)
	{
		Buffer retVal = null;
		Buffer lastBuffer = null;

		for (String arg : args)
		{
			if (arg == null)
				continue;
			else if (arg.startsWith("+line:") || arg.startsWith("+marker:"))
			{
				if (lastBuffer != null)
					gotoMarker(view, lastBuffer, arg);
				continue;
			}

			lastBuffer = openFile((View) null, parent, arg, false, null);

			if (retVal == null && lastBuffer != null)
				retVal = lastBuffer;
		}

		if(view != null && retVal != null)
		{
			if(view.getBuffer() != retVal)
			{
				view.setBuffer(retVal);
				// Although gotoMarker code is set to execute its code in a runAfterIoTasks runnable,
				// the view.setBuffer command might end up being executed after the gotoMarker code,
				// if so the caret might not be visible when the buffer is changed, so we scroll to
				// caret overhere.
				if(!view.getTextArea().isCaretVisible())
					view.getTextArea().scrollToCaret(false);
			}
		}
		
		//Harlan
		currentView = view;

		return retVal;
	} //}}}

	//{{{ scrollToLine() method
	/**
	 * Scroll to specified line. This is called from an on click
	 * node from the graphical javascript mindmap.
	 * @param args The line number to scroll to.
	 */
	public void scrollToLine(int newLineNumber) {
		
		//Harlan
		EditPane editPanes[] = currentView.getEditPanes();
		
		if (editPanes.length > 0) {

		    int lastPane = editPanes.length - 1;
		    
		    SwingUtilities.invokeLater(() -> {
		       
		    	JEditTextArea jTextArea = editPanes[lastPane].getTextArea();
		    	int visibleLines = jTextArea.getLineCount();
		    	
		    	if (newLineNumber < visibleLines) {
		    		
				    EditBus.send(new PositionChanging(editPanes[lastPane]));
				    jTextArea.setCaretPosition(jTextArea.getLineStartOffset(newLineNumber));
				    jTextArea.scrollTo(jTextArea.getLineStartOffset(newLineNumber), false);
				    
				    jTextArea.revalidate();
				    jTextArea.repaint();
		    	}
		    });
		}
	}
	
    public void scrollToLineCentered(int newLineNumber) {
    	
		//Harlan
		EditPane editPanes[] = currentView.getEditPanes();
		
		if (editPanes.length > 0) {

		    int lastPane = editPanes.length - 1;
		    
		    SwingUtilities.invokeLater(() -> {
		    	
		    	JEditTextArea jTextArea = editPanes[lastPane].getTextArea();
		        int totalLines = jTextArea.getLineCount();

		        if (newLineNumber < 0 || newLineNumber >= totalLines)
		            return;

		        // Set caret to line start to ensure it's the active line
		        int offset = jTextArea.getLineStartOffset(newLineNumber);
		        jTextArea.setCaretPosition(offset, true);  // true = do not scroll yet

		        // Calculate new first visible line to center the desired line
		        int visibleLines = jTextArea.getVisibleLines();
		        int firstVisible = Math.max(0, newLineNumber - visibleLines / 2);

		        jTextArea.setFirstLine(firstVisible);  // Scrolls so the line is centered	
		    });
		}
    }
	
	//{{{ openFile() methods
	/**
	 * Opens a file, either immediately if the application is finished starting up,
	 * or after the first view has been created if not.
	 * @param path The file path
	 *
	 * @return the buffer if succesfully loaded immediately, or null otherwise
	 *
	 * @since jEdit 4.5pre1
	 */
	public static Buffer openFileAfterStartup(String path)
	{
		if (isStartupDone())
		{
			return openFile(getActiveView(), path);
		}
		else
		{
			// These additional file names will be treated just as if they had
			// been passed on the command line
			additionalFiles.add(path);
			return null;
		}
	}

	/**
	 * Opens a file. Note that as of jEdit 2.5pre1, this may return
	 * null if the buffer could not be opened.
	 * @param view The view to open the file in
	 * @param path The file path
	 *
	 * @return the buffer, or null if jEdit was unable to load it
	 *
	 * @since jEdit 2.4pre1
	 */
	public static Buffer openFile(View view, String path)
	{
		return openFile(view,null,path,false,new Hashtable<String,Object>());
	}

	/**
	 * Opens a file. This may return null if the buffer could not be
	 * opened for some reason.
	 * @param view The view to open the file in. If it is null, the file
	 * will be opened and added to the bufferSet of the current edit pane,
	 * but not selected
	 * @param parent The parent directory of the file
	 * @param path The path name of the file
	 * @param newFile True if the file should not be loaded from disk
	 * be prompted if it should be reloaded
	 * @param props Buffer-local properties to set in the buffer
	 *
	 * @return the buffer, or null if jEdit was unable to load it
	 *
	 * @since jEdit 3.2pre10
	 */
	public static Buffer openFile(View view, String parent,
		String path, boolean newFile, Hashtable<String,Object> props)
	{
		return openFile(view == null ? null : view.getEditPane(), parent, path, newFile, props);
	}

	/**
	 * Opens a file. Note that as of jEdit 2.5pre1, this may return
	 * null if the buffer could not be opened.
	 * @param editPane the EditPane to open the file in.
	 * @param path The file path
	 *
	 * @return the buffer, or null if jEdit was unable to load it
	 *
	 * @since jEdit 4.3pre17
	 */
	public static Buffer openFile(EditPane editPane, String path)
	{
		return openFile(editPane,null,path,false,new Hashtable<String,Object>());
	}

	/**
	 * Opens a file. This may return null if the buffer could not be
	 * opened for some reason.
	 * @param editPane the EditPane to open the file in.
	 * @param parent The parent directory of the file
	 * @param path The path name of the file
	 * @param newFile True if the file should not be loaded from disk
	 * be prompted if it should be reloaded
	 * @param props Buffer-local properties to set in the buffer
	 *
	 * @return the buffer, or null if jEdit was unable to load it
	 *
	 * @since jEdit 4.3pre17
	 */
	public static Buffer openFile(EditPane editPane, String parent,
		String path, boolean newFile, Hashtable<String,Object> props)
	{
		PerspectiveManager.setPerspectiveDirty(true);

		if(editPane != null && parent == null && editPane.getBuffer() != null)
			parent = editPane.getBuffer().getDirectory();

		try
		{
			URL u = new URL(path);
			if ("file".equals(u.getProtocol()))
				path = URLDecoder.decode(u.getPath(), StandardCharsets.UTF_8);
		}
		catch (MalformedURLException e)
		{
			path = MiscUtilities.constructPath(parent,path);
		}


		if(props == null)
			props = new Hashtable<>();
		composeBufferPropsFromHistory(props, path);

		Buffer newBuffer;

		synchronized (editBusOrderingLock)
		{
			View view = editPane == null ? null : editPane.getView();
			synchronized(bufferManager.getBufferListLock())
			{
				Optional<Buffer> optionalBuffer = bufferManager.getBuffer(path);
				if (optionalBuffer.isPresent())
				{
					Buffer buffer = optionalBuffer.get();
					if(editPane != null)
						editPane.setBuffer(buffer,true);

					return buffer;
				}

				// if it is new, then it is untitled
				newBuffer = new Buffer(path,newFile,false,props,newFile);


				if (newBuffer.isBackup()) {
					Object[] args = {newBuffer.getName()};
					int result = GUIUtilities.option(view, "file-is-backup",
							args, JOptionPane.WARNING_MESSAGE,
							new String[]{
									jEdit.getProperty("file-is-backup.open"),
									jEdit.getProperty("file-is-backup.open-locked"),
									jEdit.getProperty("common.cancel")
							}, jEdit.getProperty("common.cancel"));
					if (result == 2)
						return null;
					if (result == 1)
						newBuffer.setLocked(true);
				}


				if(!newBuffer.load(view,false))
					return null;
				bufferManager.addBufferToList(newBuffer);
				if (editPane != null)
					bufferSetManager.addBuffer(editPane, newBuffer);
				else
					bufferSetManager.addBuffer(jEdit.getActiveView(), newBuffer);
			}

			EditBus.send(new BufferUpdate(newBuffer,view,BufferUpdate.CREATED));
		}

		if(editPane != null)
			editPane.setBuffer(newBuffer,true);

		return newBuffer;
	} //}}}

	//{{{ openTemporary() methods
	/**
	 * Opens a temporary buffer. A temporary buffer is like a normal
	 * buffer, except that an event is not fired and the buffer is
	 * not added to the buffers list.
	 * <p>If a buffer for the given <code>path</code> was
	 * already opened in jEdit, then this instance is returned.
	 * Otherwise jEdit will not store a reference
	 * to the returned Buffer object.
	 * <p>This method is thread-safe.
	 *
	 * @param view The view to open the file in
	 * @param parent The parent directory of the file
	 * @param path The path name of the file
	 * @param newFile True if the file should not be loaded from disk
	 *
	 * @return the buffer, or null if jEdit was unable to load it
	 *
	 * @since jEdit 3.2pre10
	 */
	public static Buffer openTemporary(View view, String parent,
		String path, boolean newFile)
	{
		return openTemporary(view, parent, path, newFile, null);
	}

	//{{{ openTemporary() methods
	/**
	 * Opens a temporary buffer. A temporary buffer is like a normal
	 * buffer, except that an event is not fired and the buffer is
	 * not added to the buffers list.
	 * <p>If a buffer for the given <code>path</code> was
	 * already opened in jEdit, then this instance is returned.
	 * Otherwise jEdit will not store a reference
	 * to the returned Buffer object.
	 * <p>This method is thread-safe.
	 *
	 * @param view The view to open the file in
	 * @param parent The parent directory of the file
	 * @param path The path name of the file
	 * @param newFile True if the file should not be loaded from disk
         * @param untitled is the buffer untitled
	 *
	 * @return the buffer, or null if jEdit was unable to load it
	 *
	 * @since jEdit 5.5pre1
	 */
	public static Buffer openTemporary(View view, String parent,
		String path, boolean newFile, boolean untitled)
	{
		return openTemporary(view, parent, path, newFile, null, untitled);
	}

        /**
	 * Opens a temporary buffer.
	 * Details: {@link #openTemporary(View, String, String, boolean)}
	 *
	 * @param view The view to open the file in
	 * @param parent The parent directory of the file
	 * @param path The path name of the file
	 * @param newFile True if the file should not be loaded from disk
	 * @param props Buffer-local properties to set in the buffer
	 *
	 * @return the buffer, or null if jEdit was unable to load it
	 *
	 * @since jEdit 4.3pre10
	 */
	public static Buffer openTemporary(View view, String parent,
		String path, boolean newFile, Hashtable<String, Object> props)
	{
            return openTemporary(view, parent, path, newFile, null, false);
        }

	/**
	 * Opens a temporary buffer.
	 * Details: {@link #openTemporary(View, String, String, boolean)}
	 *
	 * @param view The view to open the file in
	 * @param parent The parent directory of the file
	 * @param path The path name of the file
	 * @param newFile True if the file should not be loaded from disk
	 * @param props Buffer-local properties to set in the buffer
         * @param untitled is the buffer untitled
	 *
	 * @return the buffer, or null if jEdit was unable to load it
	 *
	 * @since jEdit 4.3pre10
	 */
	public static Buffer openTemporary(View view, String parent,
		String path, boolean newFile, Hashtable<String, Object> props, boolean untitled)
	{
		if(view != null && parent == null)
			parent = view.getBuffer().getDirectory();

		if(MiscUtilities.isURL(path))
		{
			if("file".equals(MiscUtilities.getProtocolOfURL(path)))
				path = path.substring(5);
		}

		path = MiscUtilities.constructPath(parent,path);

		if(props == null)
			props = new Hashtable<>();
		composeBufferPropsFromHistory(props, path);

		synchronized(bufferManager.getBufferListLock())
		{
			Optional<Buffer> bufferOptional = bufferManager.getBuffer(path);

			if(bufferOptional.isPresent())
				return bufferOptional.get();

			Buffer buffer = new Buffer(path,newFile,true,props,untitled);
			buffer.setBooleanProperty(Buffer.ENCODING_AUTODETECT, true);
			if(!buffer.load(view,false))
				return null;
			else
				return buffer;
		}
	} //}}}

	//{{{ commitTemporary() method
	/**
	 * Adds a temporary buffer to the buffer list. This must be done
	 * before allowing the user to interact with the buffer in any
	 * way.
	 * @param buffer The buffer
	 */
	public static void commitTemporary(Buffer buffer)
	{
		if(!buffer.isTemporary())
			return;

		PerspectiveManager.setPerspectiveDirty(true);

		bufferManager.addBufferToList(buffer);
		buffer.commitTemporary();

		// send full range of events to avoid breaking plugins
		EditBus.send(new BufferUpdate(buffer,null,BufferUpdate.CREATED));
		EditBus.send(new BufferUpdate(buffer,null,BufferUpdate.LOAD_STARTED));
		EditBus.send(new BufferUpdate(buffer,null,BufferUpdate.LOADED));
	} //}}}

	//{{{ newFile() methods
	/**
	 * Creates a new `untitled' file.
	 *
	 * @param view The view to create the file in
	 *
	 * @return the new buffer
	 */
	public static Buffer newFile(View view)
	{
		return newFile(view == null ? null : view.getEditPane());
	}

	/**
	 * Creates a new `untitled' file.
	 * @param view The view to create the file in
	 * @param dir The directory to create the file in
	 *
	 * @return the new buffer
	 *
	 * @since jEdit 3.1pre2
	 */
	public static Buffer newFile(View view, String dir)
	{
		EditPane editPane = null;
		if (view != null)
		{
			editPane = view.getEditPane();
		}
		else
		{
			View v = getActiveView();
			if (v != null)
			{
				editPane = v.getEditPane();
			}
		}
		return newFile(editPane, dir);
	}

	/**
	 * Creates a new `untitled' file.
	 *
	 * @param editPane The editPane to create the file in
	 *
	 * @return the new buffer
	 * @since jEdit 4.3pre17
	 */
	public static Buffer newFile(EditPane editPane)
	{
		String path;

		if(editPane != null && editPane.getBuffer() != null)
		{
			path = editPane.getBuffer().getDirectory();
		} else {
			File backupDir = MiscUtilities.prepareBackupDirectory(System.getProperty("user.home"));
			path = backupDir.getPath();
		}
		VFS vfs = VFSManager.getVFSForPath(path);
		// don't want 'New File' to create a read only buffer
		// if current file is on SQL VFS or something
		if((vfs.getCapabilities() & VFS.WRITE_CAP) == 0)
			path = System.getProperty("user.home");

		return newFile(editPane,path);
	}

	/**
	 * Creates a new `untitled' file.
	 *
	 * @param editPane The editPane to create the file in
	 * @param dir The directory to create the file in
	 *
	 * @return the new buffer
	 *
	 * @since jEdit 4.3pre17
	 */
	public static Buffer newFile(EditPane editPane, String dir)
	{
		if (editPane != null)
		{
			BufferSet bufferSet = editPane.getBufferSet();
			Buffer[] buffers = bufferSet.getAllBuffers();
			for (Buffer buf:buffers)
			{
				if (buf.isUntitled() && !buf.isDirty())
				{
					if (!MiscUtilities.getParentOfPath(buf.getPath()).equals(dir))
					{
						// Find the highest Untitled-n file
						int untitledCount = getNextUntitledBufferId();

						Buffer newBuffer = openFile(editPane,dir,"Untitled-" + untitledCount,true,null);
						jEdit.closeBuffer(editPane, buf);
						return newBuffer;
					}
					/*  if  "never mark untitled buffers dirty"
					 *  is selected, we might have contents in non-dirty
					 *  untitled buffers. We must clear those contents
					 *  if user requested new file.
					 */
					int l = buf.getLength();
					if (l > 0)
						buf.remove(0, l);
					editPane.setBuffer(buf);
					return buf;
				}
			}
		}

		// Find the highest Untitled-n file
		int untitledCount = getNextUntitledBufferId();

		return openFile(editPane,dir,"Untitled-" + untitledCount,true,null);
	} //}}}

	//}}}

	//{{{ Buffer management methods

	//{{{ closeBuffer() method
	/**
	 * Closes a buffer. If there are unsaved changes, the user is
	 * prompted if they should be saved first.
	 * @param view The view
	 * @param buffer The buffer
	 * @return True if the buffer was really closed, false otherwise
	 */
	public static boolean closeBuffer(View view, Buffer buffer)
	{
		// Wait for pending I/O requests
		if(buffer.isPerformingIO())
		{
			TaskManager.instance.waitForIoTasks();
			if(VFSManager.errorOccurred())
				return false;
		}

		boolean doNotSave = false;
		if(buffer.isDirty())
		{
			if (buffer.isUntitled() && jEdit.getBooleanProperty("suppressNotSavedConfirmUntitled"))
			{
				_closeBuffer(view, buffer, true);
				return true;
			}
			Object[] args = { buffer.getName() };
			int result = GUIUtilities.confirm(view,"notsaved",args,
				JOptionPane.YES_NO_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE);
			if(result == JOptionPane.YES_OPTION)
			{
				if(!buffer.save(view,null,true))
					return false;

				TaskManager.instance.waitForIoTasks();
				if(buffer.getBooleanProperty(BufferIORequest
					.ERROR_OCCURRED))
				{
					return false;
				}
			}
			else if(result != JOptionPane.NO_OPTION) {
				// cancel
				return false;
			}
			else if(result == JOptionPane.NO_OPTION) {
				// when we close an untitled buffer, cos we do not want to save it by answering No,
				// mark to delete the autosave file
				doNotSave = true;
			}

		}

		_closeBuffer(view,buffer, doNotSave);

		return true;
	} //}}}

	//{{{ closeBuffer() method
	/**
	 * Close a buffer.
	 * The buffer is first removed from the EditPane's bufferSet.
	 * If the buffer is not in any bufferSet after that, it is closed
	 * @param editPane the edit pane (it cannot be null)
	 * @param buffer the buffer (it cannot be null)
	 * @since jEdit 4.3pre15
	 */
	public static void closeBuffer(EditPane editPane, Buffer buffer)
	{
		switch (bufferSetManager.getScope())
		{
			case global:
				closeBuffer(editPane.getView(), buffer);
				break;
			case view:
				List<View> views = viewManager.getViews();
				int viewOwner = 0;
				for (View view : views)
				{
					BufferSet bufferSet = view.getEditPane().getBufferSet();
					// no need to check every bufferSet since it's view scope
					if (bufferSet.contains(buffer))
					{
						viewOwner++;
						if (viewOwner > 1)
							break;
					}
				}
				if (viewOwner > 1)
				{
					// the buffer is in several view, we can remove it from bufferSet
					bufferSetManager.removeBuffer(editPane, buffer);
				}
				else
				{
					closeBuffer(editPane.getView(), buffer);
				}
				break;
			case editpane:
				int bufferSetsCount = bufferSetManager.countBufferSets(buffer);
				if (bufferSetsCount < 2)
				{
					closeBuffer(editPane.getView(), buffer);
				}
				else
				{
					bufferSetManager.removeBuffer(editPane, buffer);
				}
				break;
		}
	} //}}}

	//{{{ _closeBuffer() method
	/**
	 * Closes the buffer, even if it has unsaved changes.
	 * @param view The view, may be null
	 * @param buffer The buffer
	 *
	 * @exception NullPointerException if the buffer is null
	 *
	 * @since jEdit 2.2pre1
	 */
	public static void _closeBuffer(View view, Buffer buffer)
	{
		_closeBuffer(view, buffer, true);
	}

	//{{{ _closeBuffer() method
	/**
	 * Closes the buffer, even if it has unsaved changes.
	 * @param view The view, may be null
	 * @param buffer The buffer
	 * @param doNotSave we do not want to keep the autosave file
	 *
	 * @exception NullPointerException if the buffer is null
	 *
	 * @since jEdit 2.2pre1
	 */
	public static void _closeBuffer(View view, Buffer buffer, boolean doNotSave)
	{
		if(buffer.isClosed())
		{
			// can happen if the user presses C+w twice real
			// quick and the buffer has unsaved changes
			return;
		}

		// in case of a temporary buffer, just close it
		if(buffer.isTemporary())
		{
			buffer.close();
			return;
		}

		PerspectiveManager.setPerspectiveDirty(true);

		if(!buffer.isNewFile())
		{
			if(view != null)
				view.getEditPane().saveCaretInfo();
			Integer _caret = (Integer)buffer.getProperty(Buffer.CARET);
			int caret = _caret == null ? 0 : _caret;

			BufferHistory.setEntry(buffer.getPath(),caret,
				(Selection[])buffer.getProperty(Buffer.SELECTION),
				buffer.getStringProperty(JEditBuffer.ENCODING),
				buffer.getMode().getName());
		}

		EditBus.send(new BufferUpdate(buffer,view,BufferUpdate.CLOSING));

		bufferManager.removeBuffer(buffer);

		buffer.close(doNotSave);
		DisplayManager.bufferClosed(buffer);
		bufferSetManager.removeBuffer(buffer);
		EditBus.send(new BufferUpdate(buffer,view,BufferUpdate.CLOSED));
		if(jEdit.getBooleanProperty("persistentMarkers"))
			buffer.updateMarkersFile(view);
	} //}}}

	//{{{ closeAllBuffers() methods
	/**
	 * Closes all open buffers.
	 * @param view The view
	 *
	 * @return true if all buffers were closed, false otherwise
	 */
	public static boolean closeAllBuffers(View view)
	{
		return closeAllBuffers(view,false);
	}
	/**
	 * Closes all open buffers.
	 * @param view The view
	 * @param isExiting This must be false unless this method is
	 * being called by the exit() method
	 *
	 * @return true if all buffers were closed, false otherwise
	 */
	public static boolean closeAllBuffers(View view, boolean isExiting)
	{
		if(view != null)
			view.getEditPane().saveCaretInfo();

		boolean saveRecent = !(isExiting && jEdit.getBooleanProperty("restore"));

		boolean autosaveUntitled = jEdit.getBooleanProperty("autosaveUntitled");

		boolean suppressNotSavedConfirmUntitled = jEdit.getBooleanProperty("suppressNotSavedConfirmUntitled") || autosaveUntitled;

		Optional<Buffer> firstDirty = bufferManager
			.getBuffers(Buffer::isDirty)
			.stream()
			.filter(buffer -> !(buffer.isUntitled() && suppressNotSavedConfirmUntitled))
			.findFirst();

		if (firstDirty.isPresent())
		{
			boolean ok = new CloseDialog(view).isOK();
			if(!ok)
				return false;
		}

		// Wait for pending I/O requests
		TaskManager.instance.waitForIoTasks();
		if(VFSManager.errorOccurred())
			return false;

		bufferManager.closeAllBuffers(view,
			isExiting,
			autosaveUntitled,
			saveRecent,
			jEdit.getBooleanProperty("persistentMarkers"));

		PerspectiveManager.setPerspectiveDirty(true);

		return true;
	} //}}}

	//{{{ saveAllBuffers() method
	/**
	 * Saves all open buffers.
	 * @param view The view
	 * @since jEdit 4.2pre1
	 */
	public static void saveAllBuffers(View view)
	{
		saveAllBuffers(view,jEdit.getBooleanProperty("confirmSaveAll"));
	} //}}}

	//{{{ saveAllBuffers() method
	/**
	 * Saves all open buffers.
	 * @param view The view
	 * @param confirm If true, a confirmation dialog will be shown first
	 * @since jEdit 2.7pre2
	 */
	public static void saveAllBuffers(View view, boolean confirm)
	{
		Collection<Buffer> dirtyBuffers = bufferManager.getBuffers(Buffer::isDirty);

		Buffer current = view.getBuffer();

		Stream<Buffer> toSaveBufferStream;
		if (confirm && !dirtyBuffers.isEmpty())
		{
			DefaultListModel<String> listModel = new DefaultListModel<>();
			dirtyBuffers
				.stream()
				.map(Buffer::getPath)
				.forEach(listModel::addElement);

			JList<String> bufferList = new JList<>(listModel);
			bufferList.setVisibleRowCount(Math.min(listModel.getSize(), 10));
			bufferList.setSelectionInterval(0, listModel.getSize() - 1);

			int result = JOptionPane.showConfirmDialog(view,
				new Object[]{ new JLabel(jEdit.getProperty("saveall.message")), new JScrollPane(bufferList) },
				jEdit.getProperty("saveall.title"),
				JOptionPane.YES_NO_OPTION,
				JOptionPane.QUESTION_MESSAGE);
			if(result != JOptionPane.YES_OPTION)
				return;

			List<String> dirtySelectedBuffers = bufferList.getSelectedValuesList();
			toSaveBufferStream = dirtyBuffers
				.stream()
				.filter(buffer -> dirtySelectedBuffers.contains(buffer.getPath()));
		}
		else
		{
			toSaveBufferStream = dirtyBuffers.stream();
		}
		toSaveBufferStream.forEach(buffer ->
		{
			if (buffer.isNewFile()) view.setBuffer(buffer);
			buffer.save(view, null, true, true);
		});

		view.setBuffer(current);
	} //}}}

	//{{{ reloadAllBuffers() method
	/**
	 * Reloads all open buffers.
	 * @param view The view
	 * @param confirm If true, a confirmation dialog will be shown first
	 *	if any buffers are dirty
	 * @since jEdit 2.7pre2
	 */
	public static void reloadAllBuffers(View view, boolean confirm)
	{
		List<Buffer> titledBuffers = jEdit.getBufferManager().getTitledBuffers();

		if (confirm)
		{
			// Find a dirty buffer that is not untitled
			Optional<Buffer> dirtyBuffer = titledBuffers
				.stream()
				.filter(JEditBuffer::isDirty)
				.findFirst();
			if (dirtyBuffer.isPresent())
			{
				int result = GUIUtilities.confirm(view,"reload-all",null,
					JOptionPane.YES_NO_OPTION,
					JOptionPane.QUESTION_MESSAGE);
				if(result != JOptionPane.YES_OPTION)
					return;
			}
		}

		// save caret info. Buffer.load() will load it.
		visit(new SaveCaretInfoVisitor());

		titledBuffers.forEach(buffer -> buffer.load(view, true));
	} //}}}

	//{{{ _getBuffer() method
	/**
	 * Returns the buffer with the specified path name. The path name
	 * must be an absolute, canonical, path.
	 *
	 * @param path The path name
	 *
	 * @return the searched buffer, or null if it is not already open
	 *
	 * @see MiscUtilities#constructPath(String,String)
	 * @see MiscUtilities#resolveSymlinks(String)
	 * @see BufferManager#getBuffer(String)
	 *
	 * @since jEdit 4.2pre7
	 */
	@Deprecated(since = "5.6pre1")
	public static Buffer _getBuffer(String path)
	{
		return bufferManager._getBuffer(path).orElse(null);
	} //}}}

	//{{{ getBuffer() method
	/**
	 * Returns the buffer with the specified path name. The path name
	 * must be an absolute path. This method automatically resolves
	 * symbolic links. If performance is critical, cache the canonical
	 * path and call {@link BufferManager#getBuffer(String)} instead.
	 *
	 * @param path The path name
	 *
	 * @return the searched buffer, or null if it is not already open
	 *
	 * @see MiscUtilities#constructPath(String,String)
	 * @see MiscUtilities#resolveSymlinks(String)
	 */
	@Deprecated(since = "5.6pre1")
	public static Buffer getBuffer(String path)
	{
		return bufferManager.getBuffer(path).orElse(null);
	} //}}}

	//{{{ getBuffers() method
	/**
	 * Returns an array of all open buffers from any View.
	 * @return  an array of all open buffers
	 * @see View#getBuffers()
	 */
	@Deprecated(since = "5.6pre1")
	public static Buffer[] getBuffers()
	{
		return bufferManager.getBuffers().toArray(BufferManagerImpl.EMPTY_BUFFER_ARRAY);
	} //}}}

	//{{{ getBufferCount() method
	/**
	 * Returns the number of open buffers.
	 */
	public static int getBufferCount()
	{
		return bufferManager.size();
	} //}}}

	//{{{ getFirstBuffer() method
	/**
	 * Returns the first buffer.
	 */
	public static Buffer getFirstBuffer()
	{
		return bufferManager.getFirst();
	} //}}}

	//{{{ getLastBuffer() method
	/**
	 * Returns the last buffer.
	 * @return the last buffer
	 */
	public static Buffer getLastBuffer()
	{
		return bufferManager.getLast();
	} //}}}

	//{{{ moveBuffer() method
	/**
	 * Moves a buffer from a old position to a new position in the
	 * BufferSet used in an EditPane.
	 * @param editPane The EditPane in which a buffer is moved
	 * @param oldPosition The position before the move
	 * @param newPosition The position after the move
	 */
	public static void moveBuffer(EditPane editPane,
		int oldPosition, int newPosition)
	{
		bufferSetManager.moveBuffer(editPane, oldPosition, newPosition);
	} //}}}

	//{{{ getBufferSetManager() method
	/**
	 * Returns the bufferSet manager.
	 * @return the bufferSetManager
	 * @since jEdit 4.3pre15
	 */
	public static BufferSetManager getBufferSetManager()
	{
		return bufferSetManager;
	} //}}}

	//{{{ getBufferManager() method
	/**
	 * Returns the buffer manager
	 * @return the buffer manager
	 * @since jEdit 5.6pre1
	 */
	public static BufferManager getBufferManager()
	{
		return bufferManager;
	} //}}}

	//{{{ getEditPaneManager() method
	/**
	 * Returns the Edit Pane manger
	 * @return the edit pane manager
	 * @since jEdit 5.6pre1
	 */
	public static EditPaneManager getEditPaneManager()
	{
		return editPaneManager;
	} //}}}

	//{{{ getViewManager() method
	/**
	 * Returns the view manager
	 * @return the view manager
	 * @since jEdit 5.6pre1
	 */
	public static ViewManager getViewManager()
	{
		return viewManager;
	} //}}}

	//{{{ getPropertyManager() method
	/**
	 * @return the propertyManager
	 * @since jEdit 4.3pre15
	 */
	public static JEditPropertyManager getPropertyManager()
	{
		return propertyManager;
	} //}}}

	//{{{ checkBufferStatus() methods
	/**
	 * Checks each buffer's status on disk and shows the dialog box
	 * informing the user that buffers changed on disk, if necessary.
	 * @param view The view
	 * @since jEdit 4.2pre1
	 */
	public static void checkBufferStatus(View view)
	{
		checkBufferStatus(view,false);
	}

	/**
	 * Checks buffer status on disk and shows the dialog box
	 * informing the user that buffers changed on disk, if necessary.
	 * @param view The view
	 * @param currentBuffer indicates whether to check only the current buffer
	 * @since jEdit 4.2pre1
	 */
	public static void checkBufferStatus(View view, boolean currentBuffer)
	{
		Log.log(Log.DEBUG, jEdit.class, "checkBufferStatus for " +
			(currentBuffer ? "current buffer: " + view.getBuffer() : "all buffers"));
		visit(new SaveCaretInfoVisitor());
		bufferManager.checkBufferStatus(view, currentBuffer, getBooleanProperty("autoReload"));
	} //}}}

	//}}}

	//{{{ View methods

	//{{{ getInputHandler() method
	/**
	 * Returns the current input handler (key binding to action mapping)
	 * @see org.gjt.sp.jedit.gui.InputHandler
	 */
	public static InputHandler getInputHandler()
	{
		return inputHandler;
	} //}}}

	//{{{ newView() methods
	/**
	 * Creates a new view.
	 * @param view An existing view
	 * @since jEdit 3.2pre2
	 */
	public static View newView(View view)
	{
		return newView(view,null,false);
	}
	/**
	 * Creates a new view of a buffer.
	 * @param view An existing view
	 * @param buffer The buffer
	 */
	public static View newView(View view, Buffer buffer)
	{
		return newView(view,buffer,false);
	}
	/**
	 * Creates a new view of a buffer.
	 * @param view An existing view
	 * @param buffer The buffer
	 * @param plainView If true, the view will not have dockable windows or
	 * tool bars.
	 *
	 * @since 4.1pre2
	 */
	public static View newView(View view, Buffer buffer, boolean plainView)
	{
		View.ViewConfig config;
		if(view != null && (plainView == view.isPlainView()))
		{
			config = view.getViewConfig();
			config.x -= 20;
			config.y += 20;
		}
		else
		{
			config = new View.ViewConfig(plainView);
		}
		return newView(view,buffer,config);
	}

	/**
	 * Creates a new view.
	 * @param view An existing view
	 * @param buffer A buffer to display, or null
	 * @param config Encapsulates the view geometry, split configuration
	 * and if the view is a plain view
	 * @since jEdit 4.2pre1
	 */
	public static View newView(View view, Buffer buffer, View.ViewConfig config)
	{
		// Mark the perspective as dirty, unless the new view is created
		// during jEdit startup, by the loading of the perspective.
		if (isStartupDone())
			PerspectiveManager.setPerspectiveDirty(true);

		try
		{
			if(view != null)
			{
				view.showWaitCursor();
				view.getEditPane().saveCaretInfo();
			}

			View newView = new View(buffer,config);
			viewManager.addViewToList(newView);

			EditBus.send(new ViewUpdate(newView,ViewUpdate.CREATED));

			newView.pack();
			newView.adjust(view, config);
			newView.setVisible(true);

			if(!config.plainView)
			{
				int index;
				synchronized (startupDone)
				{
					index = startupDone.size();
					startupDone.add(false);
				}
				EventQueue.invokeLater(new DockingLayoutSetter(
					newView, config, index));
			}

			// show tip of the day
			if(newView == viewManager.getFirst())
			{
				newView.getTextArea().requestFocus();

				// Don't show the welcome message if jEdit was started
				// with the -nosettings switch
				if(settingsDirectory != null && getBooleanProperty("firstTime"))
					new HelpViewer("welcome.html");
				else if(jEdit.getBooleanProperty("tip.show"))
					new TipOfTheDay(newView);

				setBooleanProperty("firstTime",false);
			}
			else
				GenericGUIUtilities.requestFocus(newView,newView.getTextArea());

			return newView;
		}
		finally
		{
			if(view != null)
				view.hideWaitCursor();
		}
	} //}}}

	//{{{ closeView() method
	/**
	 * Closes a view.
	 *
	 * jEdit will exit if this was the last open view.
	 */
	public static void closeView(View view)
	{
		closeView(view,true);
	} //}}}

	//{{{ getViews() method
	/**
	 * Returns an array of all open views.
	 */
	@Deprecated(since = "5.6pre1")
	public static View[] getViews()
	{
		return viewManager.getViews().toArray(ViewManagerImpl.EMPTY_VIEW_ARRAY);
	} //}}}

	//{{{ getViewCount() method
	/**
	 * Returns the number of open views.
	 */
	public static int getViewCount()
	{
		return viewManager.size();
	} //}}}

	//{{{ getFirstView() method
	/**
	 * Returns the first view.
	 */
	public static View getFirstView()
	{
		return viewManager.getFirst();
	} //}}}

	//{{{ getLastView() method
	/**
	 * Returns the last view.
	 */
	public static View getLastView()
	{
		return viewManager.getLast();
	} //}}}

	//{{{ getActiveView() method
	/**
	 * Returns the currently focused view.
	 * @since jEdit 4.1pre1
	 */
	public static View getActiveView()
	{
		return viewManager.getActiveView();
	} //}}}

	//}}}

	//{{{ Miscellaneous methods

	//{{{ relocateSettings() method
	public static void relocateSettings()
	{
		String oldSettingsPath = MiscUtilities.constructPath(
				System.getProperty("user.home"),
				".jedit");
		File oldSettingsDir = new File(oldSettingsPath);
		File newSettingsDir = new File(settingsDirectory);
		if(oldSettingsDir.exists() && !newSettingsDir.exists())
		{
			Log.log(Log.NOTICE,jEdit.class,"Old settings directory found (HOME/.jedit). Moving to new location ("+newSettingsDir+ ')');
			try
			{
				oldSettingsDir.renameTo(newSettingsDir);
			}
			catch(SecurityException se)
			{
				Log.log(Log.ERROR,jEdit.class,se);
			}
		}
	}
	//}}}

	//{{{ isStartupDone() method
	/**
	 * Whether jEdit startup is over.
	 * @since jEdit 4.3pre17
	 */
	public static boolean isStartupDone()
	{
		return (! startupDone.contains(false));
	} //}}}

	//{{{ isMainThread() method
	/**
	 * Returns true if the currently running thread is the main thread.
	 * @since jEdit 4.2pre1
	 */
	public static boolean isMainThread()
	{
		return Thread.currentThread() == mainThread;
	} //}}}

	//{{{ isBackgroundMode() method
	/**
	 * Returns true if jEdit was started with the <code>-background</code>
	 * command-line switch.
	 * @since jEdit 4.0pre4
	 */
	public static boolean isBackgroundModeEnabled()
	{
		return background;
	} //}}}

	//{{{ showMemoryDialog() method
	/**
	 * Performs garbage collection and displays a dialog box showing
	 * memory status.
	 * @param view The view
	 * @since jEdit 4.0pre1
	 */
	public static void showMemoryDialog(View view)
	{
		Runtime rt = Runtime.getRuntime();
		long usedBefore = rt.totalMemory() - rt.freeMemory();
		System.gc();
		long free = rt.freeMemory();
		long total = rt.totalMemory();
		long used = total - free;

		int totalKb = (int) (total / 1024);
		int usedKb = (int) (used / 1024);
		JProgressBar progress = new JProgressBar(0,totalKb);
		progress.setValue(usedKb);
		progress.setStringPainted(true);
		progress.setString(jEdit.getProperty("memory-status.use",
			new Object[] { usedKb, totalKb }));

		Object[] message = new Object[4];
		message[0] = getProperty("memory-status.gc",
			new Object[] { (usedBefore - used) / 1024 });
		message[1] = Box.createVerticalStrut(12);
		message[2] = progress;
		message[3] = Box.createVerticalStrut(6);

		JOptionPane.showMessageDialog(view,message,
			jEdit.getProperty("memory-status.title"),
			JOptionPane.INFORMATION_MESSAGE);
	} //}}}

	//{{{ getJEditHome() method
	/**
	 * Returns the jEdit install directory.
	 */
	public static String getJEditHome()
	{
		return jEditHome;
	} //}}}

	//{{{ getSettingsDirectory() method
	/**
	 * Returns the path of the directory where user-specific settings
	 * are stored. This will be <code>null</code> if jEdit was
	 * started with the <code>-nosettings</code> command-line switch; do not
	 * blindly use this method without checking for a <code>null</code>
	 * return value first. <p>
	 *
	 * <b>NOTE</b>: plugins should <b>not</b> use this directory as a base to
	 * store their files. Instead, they should use EditPlugin.getPluginHome().
	 * @see EditPlugin#getPluginHome()
	 */
	public static String getSettingsDirectory()
	{
		return settingsDirectory;
	} //}}}

	//{{{ getJARCacheDirectory() method
	/**
	 * Returns the directory where plugin cache files are stored.
	 * @since jEdit 4.2pre1
	 */
	public static String getJARCacheDirectory()
	{
		return jarCacheDirectory;
	} //}}}

	//{{{ backupSettingsFile() method
	/**
	 * Backs up the specified file in the settings directory.
	 * You should call this on any settings files your plugin
	 * writes.
	 * @param file The file
	 * @since jEdit 4.0pre1
	 */
	public static void backupSettingsFile(File file)
	{
		if(settingsDirectory == null || !file.exists())
			return;

		String backupDir = MiscUtilities.constructPath(
			settingsDirectory,"settings-backup");
		File dir = new File(backupDir);
		if(!dir.exists())
			dir.mkdirs();

		// ... sweet. saveBackup() will create backupDir if it
		// doesn't exist.

		MiscUtilities.saveBackup(file,5,null,"~",backupDir);
	} //}}}

	//{{{ saveSettings() method
	/**
	 * Saves all user preferences to disk.
	 */
	public static void saveSettings()
	{
		if(settingsDirectory == null)
			return;

		Abbrevs.save();
		keymapManager.getKeymap().save();
		FavoritesVFS.saveFavorites();
		HistoryModel.saveHistory();
		Registers.saveRegisters();
		SearchAndReplace.save();
		BufferHistory.save();
		KillRing.getInstance().save();

		File file1 = new File(MiscUtilities.constructPath(
			settingsDirectory,"#properties#save#"));
		File file2 = new File(MiscUtilities.constructPath(
			settingsDirectory,"properties"));
		if(file2.exists() && file2.lastModified() != propsModTime)
		{
			Log.log(Log.WARNING,jEdit.class,file2 + " changed"
				+ " on disk; will not save user properties");
		}
		else
		{
			backupSettingsFile(file2);
			OutputStream out = null;
			try
			{
				out = new FileOutputStream(file1);
				propMgr.saveUserProps(out);
			}
			catch(IOException io)
			{
				Log.log(Log.ERROR,jEdit.class,io);
			}
			finally
			{
				IOUtilities.closeQuietly((Closeable)out);
			}
			file2.delete();
			if (! file1.renameTo(file2))
			{
				Log.log(Log.ERROR,jEdit.class,"Failed to rename \"" + file1 +
					"\" to the user properties file \"" + file2 + "\".");
			}
			propsModTime = file2.lastModified();
		}
	} //}}}

	//{{{ exit() method
	/**
	 * Exits cleanly from jEdit, prompting the user if any unsaved files
	 * should be saved first.
	 * @param view The view from which this exit was called
	 * @param reallyExit If background mode is enabled and this parameter
	 * is true, then jEdit will close all open views instead of exiting
	 * entirely.
	 */
	public static void exit(View view, boolean reallyExit)
	{
		// Close dialog, view.close() call need a view...
		if(view == null)
			view = viewManager.getActiveViewInternal();

		// Wait for pending I/O requests
		TaskManager.instance.waitForIoTasks();

		// Create a new EditorExitRequested
		EditorExitRequested eer = new EditorExitRequested(view);

		// Send EditorExitRequested
		EditBus.send(eer);

		// Check if the ExitRequest has been cancelled
		// if so, do not proceed anymore in the exiting
		if (eer.hasBeenExitCancelled())
		{
			Log.log(Log.MESSAGE, jEdit.class, "Exit has been cancelled");
			return;
		}

		// Even if reallyExit is false, we still exit properly
		// if background mode is off
		reallyExit |= !background;

		PerspectiveManager.savePerspective(false);

		try
		{
			PerspectiveManager.setPerspectiveEnabled(false);

			// Close all buffers
			if(!closeAllBuffers(view,reallyExit))
				return;
		}
		finally
		{
			PerspectiveManager.setPerspectiveEnabled(true);
		}

		// If we are running in background mode and
		// reallyExit was not specified, then return here.
		if(!reallyExit)
		{
			// in this case, we can't directly call
			// view.close(); we have to call closeView()
			// for all open views
			viewManager.forEach(v -> closeView(v, false));

			// Save settings in case user kills the backgrounded
			// jEdit process
			saveSettings();
		}
		else
		{
			// Send EditorExiting
			EditBus.send(new EditorExiting(null));

			// Save view properties here
			viewManager.forEach(v -> closeView(v, false));

			// Stop autosave timer
			Autosave.stop();

			// Stop server
			if(server != null)
				server.stopServer();

			// Stop all plugins
			PluginJAR[] plugins = getPluginJARs();
			for (PluginJAR plugin : plugins)
				removePluginJAR(plugin, true);

			// Save settings
			saveSettings();

			// Close activity log stream
			Log.closeStream();

			// Byebye...
			System.exit(0);
		}
	} //}}}

	//{{{ getEditServer() method
	/**
	 * Returns the edit server instance. You can use this to find out the
	 * port number jEdit is listening on.
	 * @since jEdit 4.2pre10
	 */
	public static EditServer getEditServer()
	{
		return server;
	} //}}}

	//{{{ visit() method
	/**
	 * Visit the views, editpanes and textareas
	 *
	 * @see org.gjt.sp.jedit.manager.ViewManager#forEach(Consumer)
	 * @see org.gjt.sp.jedit.manager.BufferManager#forEach(Consumer)
	 *
	 * @param visitor the visitor
	 * @since jEdit 4.3pre13
	 */
	public static void visit(JEditVisitor visitor)
	{
		viewManager.forEach(view ->
		{
			visitor.visit(view);
			view.visit(visitor);
		});
	} //}}}

	//{{{ getRegisterStatusPrompt() method
	/**
	 * Returns the status prompt for the given register action. Only
	 * intended to be called from <code>actions.xml</code>.
	 * @since jEdit 4.3pre16
	 */
	public static String getRegisterStatusPrompt(String action)
	{
		String registerNameString = Registers.getRegisterNameString();
		return jEdit.getProperty("view.status." + action,
			new String[] {registerNameString == null ?
				      jEdit.getProperty("view.status.no-registers") :
				      registerNameString});
	} //}}}

	//{{{ getKeyMapManager() method
	public static KeymapManager getKeymapManager()
	{
		return keymapManager;
	} //}}}

	//{{{ logTime(String) method
	/** Logs time since startup, for benchmarking */
	private static void logTime(String label)
	{
		long currentTime = System.currentTimeMillis();
		Log.log(Log.DEBUG, jEdit.class,
			label + ':' + (currentTime - startupTime) + " ms");
	} //}}}

	//}}} Miscellaneous methods fold end

	//{{{ Package-private members

	//{{{ updatePosition() method
	/**
	 * If buffer sorting is enabled, this repositions the buffer.
	 */
	static void updatePosition(String oldPath, Buffer buffer)
	{
		bufferManager.updatePosition(oldPath, buffer);
	}

	//{{{ loadMode() method
	/**
	 * Loads an XML-defined edit mode from the specified reader.
	 * @param mode The edit mode
	 */
	/* package-private */ static void loadMode(Mode mode)
	{
		final String fileName = (String)mode.getProperty("file");
		XModeHandler xmh = new XModeHandler(mode.getName())
		{
			@Override
			public void error(String what, Object subst)
			{
				String msg;

				Object line = "<unknown>";
				if(subst == null)
					msg = jEdit.getProperty("xmode-error." + what);
				else
				{
					msg = jEdit.getProperty("xmode-error." + what,
						new String[] { subst.toString() });
					if(subst instanceof Throwable)
						Log.log(Log.ERROR,this,subst);
					if (subst instanceof SAXParseException)
					{
						line = ((SAXParseException)subst).getLineNumber();
					}
				}

				Object[] args = { fileName, line, null, msg };
				GUIUtilities.error(null,"xmode-error",args);
			}

			@Override
			public TokenMarker getTokenMarker(String modeName)
			{
				Mode mode = getMode(modeName);
				if(mode == null)
					return null;
				else
					return mode.getTokenMarker();
			}
		};
		ModeProvider.instance.loadMode(mode, xmh);
	} //}}}

	//{{{ addPluginProps() method
	static void addPluginProps(Properties map)
	{
		propMgr.addPluginProps(map);
	} //}}}

	//{{{ removePluginProps() method
	static void removePluginProps(Properties map)
	{
		propMgr.removePluginProps(map);
	} //}}}

	//{{{ addPluginLocalizationProps() method
	static void addPluginLocalizationProps(Properties map)
	{
		propMgr.addPluginLocalizationProps(map);
	} //}}}

	//{{{ removePluginLocalizationProps() method
	static void removePluginLocalizationProps(Properties map)
	{
		propMgr.removePluginLocalizationProps(map);
	} //}}}

	//{{{ pluginError() method
	/**
	 * @param path
	 * @param messageProp - a property of a message to print
	 * @param args a list of arguments which correspond to {0} and {1} in the string to print.
	 */
	static void pluginError(String path, String messageProp,
		Object[] args)
	{
		synchronized(pluginErrorLock)
		{
			if(pluginErrors == null)
				pluginErrors = new Vector<>();

			ErrorListDialog.ErrorEntry newEntry =
				new ErrorListDialog.ErrorEntry(
				path,messageProp,args);

			for (ErrorListDialog.ErrorEntry pluginError : pluginErrors)
			{
				if (pluginError.equals(newEntry))
					return;
			}
			pluginErrors.addElement(newEntry);

			if(isStartupDone())
			{
				EventQueue.invokeLater(jEdit::showPluginErrorDialog);
			}
		}
	} //}}}

	//{{{ setActiveView() method
	static void setActiveView(View view)
	{
		viewManager.setActiveView(view);
	} //}}}

	//{{{ getActiveViewInternal() method
	/**
	 * Returns the internal active view, which might be null.
	 *
	 * @since 4.3pre10
	 */
	@Nullable
	public static View getActiveViewInternal()
	{
		return viewManager.getActiveViewInternal();
	} //}}}
	//}}}

	//{{{ Private members

	//{{{ Static variables
	private static String jEditHome;
	private static String settingsDirectory;
	private static String jarCacheDirectory;
	private static long propsModTime;
	private static PropertyManager propMgr = new PropertyManager();
	private static EditServer server;
	private static boolean background;
	private static ActionContext actionContext;
	private static ActionSet builtInActionSet;
	private static Vector<ErrorListDialog.ErrorEntry> pluginErrors;
	private static final Object pluginErrorLock = new Object();
	private static Vector<PluginJAR> jars;
	private static final JEditPropertyManager propertyManager =
	                     new JEditPropertyManager();
	private static final long startupTime = System.currentTimeMillis();

	private static boolean saveCaret;
	private static InputHandler inputHandler;
	private static KeymapManager keymapManager;

	private static BufferSetManager bufferSetManager;
	private static BufferManagerImpl bufferManager = new BufferManagerImpl();
	private static ViewManagerImpl viewManager = new ViewManagerImpl();
	private static EditPaneManager editPaneManager = new EditPaneManagerImpl(viewManager);

	private static final Object editBusOrderingLock	= new Object();

	private static final List<Boolean> startupDone = new Vector<>();
	private static final Vector<String> additionalFiles = new Vector<>();

	private static Thread mainThread;
	//}}}

	public jEdit() {}

	//{{{ usage() method
	private static void usage()
	{
		System.out.println("Usage: jedit [<options>] [<files>]");

		System.out.println("	<file> +marker:<marker>: Positions caret"
			+ " at marker <marker>");
		System.out.println("	<file> +line:<line>: Positions caret"
			+ " at line number <line>");
		System.out.println("	<file> +line:<line>,<column>: Positions caret"
			+ " at line number <line> and column number <column>");
		System.out.println("	--: End of options");
		System.out.println("	-background: Run in background mode");
		System.out.println("	-nobackground: Disable background mode (default)");
		System.out.println("	-gui: Only if running in background mode; open initial view (default)");
		System.out.println("	-nogui: Only if running in background mode; don't open initial view");
		System.out.println("	-log=<level>: Log messages with level equal to or higher than this to");
		System.out.println("	 standard error. <level> must be between 1 and 9. Default is 7.");
		System.out.println("	-newplainview: Client instance opens a new plain view");
		System.out.println("	-newview: Client instance opens a new view (default)");
		System.out.println("	-plugins: Load plugins (default)");
		System.out.println("	-noplugins: Don't load any plugins");
		System.out.println("	-restore: Restore previously open files (default)");
		System.out.println("	-norestore: Don't restore previously open files");
		System.out.println("	-reuseview: Client instance reuses existing view");
		System.out.println("	-quit: Quit a running instance");
		System.out.println("	-run=<script>: Run the specified BeanShell script");
		System.out.println("	-server: Read/write server info from/to $HOME/.jedit/server (default)");
		System.out.println("	-server=<name>: Read/write server info from/to $HOME/.jedit/<name>");
		System.out.println("	-noserver: Don't start edit server");
		System.out.println("	-settings=<path>: Load user-specific settings from <path>");
		System.out.println("	-nosettings: Don't load user-specific settings");
		System.out.println("	-nosplash: Don't show splash screen");
		System.out.println("	-startupscripts: Run startup scripts (default)");
		System.out.println("	-nostartupscripts: Don't run startup scripts");
		System.out.println("	-usage: Print this message and exit");
		System.out.println("	-version: Print jEdit version and exit");
		System.out.println("	-wait: Wait until the user closes the specified buffer in the server");
		System.out.println("	 instance. Does nothing if passed to the initial jEdit instance.");
		System.out.println();
		System.out.println("Report bugs to http://sourceforge.net/tracker/?group_id=588&atid=100588");
	} //}}}

	//{{{ version() method
	private static void version()
	{
		System.out.println("jEdit " + getVersion());
	} //}}}

	//{{{ makeServerScript() method
	/**
	 * Creates a BeanShell script that can be sent to a running edit server.
	 */
	private static String makeServerScript(boolean wait,
		boolean restore, boolean newView,
		boolean newPlainView, String[] args,
		String scriptFile)
	{
		StringBuilder script = new StringBuilder();

		String userDir = System.getProperty("user.dir");

		script.append("parent = \"");
		script.append(StandardUtilities.charsToEscapes(userDir));
		script.append("\";\n");

		script.append("args = new String[");
		script.append(args.length);
		script.append("];\n");

		for(int i = 0; i < args.length; i++)
		{
			script.append("args[");
			script.append(i);
			script.append("] = ");

			if(args[i] == null)
				script.append("null");
			else
			{
				script.append('"');
				script.append(StandardUtilities.charsToEscapes(args[i]));
				script.append('"');
			}

			script.append(";\n");
		}

		script.append("view = jEdit.getLastView();\n");
		script.append("buffer = EditServer.handleClient(");
		script.append(restore).append(',').append(newView).append(',').append(newPlainView);
		script.append(",parent,args);\n");
		script.append("if(buffer != null && ").append(wait).append(") {\n");
		script.append("\tbuffer.setWaitSocket(socket);\n");
		script.append("\tdoNotCloseSocket = true;\n");
		script.append("}\n");
		script.append("if(view != jEdit.getLastView() && ").append(wait).append(") {\n");
		script.append("\tjEdit.getLastView().setWaitSocket(socket);\n");
		script.append("\tdoNotCloseSocket = true;\n");
		script.append("}\n");
		script.append("if(doNotCloseSocket == void)\n");
		script.append("\tsocket.close();\n");

		if(scriptFile != null)
		{
			scriptFile = MiscUtilities.constructPath(userDir,scriptFile);
			script.append("BeanShell.runScript(view,\"")
				.append(StandardUtilities.charsToEscapes(scriptFile))
				.append("\",null,this.namespace);\n");
		}

		return script.toString();
	} //}}}

	//{{{ initMisc() method
	/**
	 * Initialise various objects, register protocol handlers.
	 */
	private static void initMisc()
	{
		ModeProvider.instance = new ModeProvider()
		{
			@Override
			protected void error(String fileName, Throwable e)
			{
				Log.log(Log.ERROR, this, e);
				if (e instanceof SAXParseException)
				{
					String message = e.getMessage();
					int line = ((SAXParseException)e).getLineNumber();
					int col = ((SAXParseException)e).getColumnNumber();

					Object[] args = { fileName, line, col, message };
					GUIUtilities.error(null,"xmode-error",args);
				}
			}
		};
		jars = new Vector<>();
		FoldHandler.foldHandlerProvider = new ServiceManager.ServiceFoldHandlerProvider();
		actionContext = new ActionContext()
		{
			@Override
			public void invokeAction(EventObject evt,
				EditAction action)
			{
				View view = GUIUtilities.getView(
					(Component)evt.getSource());

				boolean actionBarVisible;
				if(view.getActionBar() == null
					|| !view.getActionBar().isShowing())
					actionBarVisible = false;
				else
				{
					actionBarVisible = view.getActionBar()
						.isVisible();
				}

				view.getInputHandler().invokeAction(action);

				if(actionBarVisible)
				{
					// XXX: action bar might not be 'temp'
					ActionBar actionBar = view
						.getActionBar();
					if(actionBar != null)
						view.removeToolBar(actionBar);
				}
			}
		};

		File userKeymapFolder = null;
		if (settingsDirectory != null)
		{
			userKeymapFolder = new File(settingsDirectory, "keymaps");
		}
		inputHandler = new DefaultInputHandler(null);
		// Add our protocols to java.net.URL's list
		System.getProperties().put("java.protocol.handler.pkgs",
			"org.gjt.sp.jedit.proto|" +
			System.getProperty("java.protocol.handler.pkgs",""));

		// Set the User-Agent string used by the java.net HTTP handler
		String userAgent = "jEdit/" + getVersion()
			+ " (Java " + System.getProperty("java.version")
			+ ". " + System.getProperty("java.vendor")
			+ "; " + System.getProperty("os.arch") + ')';
		System.getProperties().put("http.agent",userAgent);

		/* Determine installation directory.
		 * If the jedit.home property is set, use that.
		 * Then, look for jedit.jar in the classpath.
		 * If that fails, assume this is the web start version. */
		jEditHome = System.getProperty("jedit.home");
		if(jEditHome == null)
		{
			String classpath = System
				.getProperty("java.class.path");
			int index = classpath.toLowerCase()
				.indexOf("jedit.jar");
			int start = classpath.lastIndexOf(File
				.pathSeparator,index) + 1;
			// if started with java -jar jedit.jar
			if(start == index)
			{
				jEditHome = System.getProperty("user.dir");
			}
			else if(index > start)
			{
				jEditHome = classpath.substring(start,
					index - 1);
			}
			else
			{
				// check if web start
				/* if(jEdit.class.getResource("/modes/catalog") != null)
				{
					// modes bundled in; hence web start
					jEditHome = null;
				}
				else */
				{
					// use user.dir as last resort
					jEditHome = System.getProperty("user.dir");

					Log.log(Log.WARNING,jEdit.class,"jedit.jar not in class path!");
					Log.log(Log.WARNING,jEdit.class,"Assuming jEdit is installed in "
						+ jEditHome + '.');
					Log.log(Log.WARNING,jEdit.class,"Override with jedit.home "
						+ "system property.");
				}
			}
		}

		jEditHome = MiscUtilities.resolveSymlinks(jEditHome);

		Log.log(Log.MESSAGE,jEdit.class,"jEdit home directory is " + jEditHome);
		keymapManager = new KeymapManagerImpl(propertyManager,
		      new File(jEditHome, "keymaps"),
		      userKeymapFolder);

		if(settingsDirectory != null)
		{
			jarCacheDirectory = MiscUtilities.constructPath(
				settingsDirectory,"jars-cache");
			new File(jarCacheDirectory).mkdirs();
		}

		//if(jEditHome == null)
		//	Log.log(Log.DEBUG,jEdit.class,"Web start mode");

		// Add an EditBus component that will reload edit modes and
		// macros if they are changed from within the editor
		EditBus.addToBus(new SettingsReloader());

		// Set the ContextClassLoader for the main jEdit thread.
		// This way, the ContextClassLoader will be a JARClassLoader
		// even at plugin activation and the EventQueue can also pick
		// up the JARClassLoader. That's why this call has to be done
		// before the usage of the EventQueue.
		Thread.currentThread().setContextClassLoader(new JARClassLoader());
		// Perhaps if Xerces wasn't slightly brain-damaged, we would
		// not need this
		EventQueue.invokeLater(() -> Thread.currentThread().setContextClassLoader(new JARClassLoader()));
	} //}}}

	//{{{ getResourceAsUTF8Text() method
	private static Reader getResourceAsUTF8Text(String name)
		throws IOException
	{
		InputStream bytes = jEdit.class.getResourceAsStream(name);
		if (bytes == null)
		{
			return null;
		}
		Reader text = null;
		try
		{
			// Using our CharsetEncoding to reliably detect encoding errors.
			Encoding utf8 = new CharsetEncoding(StandardCharsets.UTF_8);
			text = utf8.getTextReader(bytes);
		}
		finally
		{
			if (text == null)
			{
				bytes.close();
			}
		}
		return text;
	} //}}}

	//{{{ initSystemProperties() method
	/**
	 * Load system properties.
	 */
	private static void initSystemProperties()
	{
		try
		{
			propMgr.loadSystemProps(getResourceAsUTF8Text(
				"/org/gjt/sp/jedit/jedit.props"));
			propMgr.loadSystemProps(getResourceAsUTF8Text(
				"/org/gjt/sp/jedit/jedit_gui.props"));
			propMgr.loadSystemProps(getResourceAsUTF8Text(
				"/org/jedit/localization/jedit_en.props"));
		}
		catch(Exception e)
		{
			Log.log(Log.ERROR,jEdit.class,
				"Error while loading system properties!");
			Log.log(Log.ERROR,jEdit.class,
				"One of the following property files could not be loaded:\n"
				+ "- jedit.props\n"
				+ "- jedit_gui.props\n"
				+ "- jedit_en.props\n"
				+ "jedit.jar is probably corrupt.");
			Log.log(Log.ERROR,jEdit.class,e);
			System.exit(1);
		}
	} //}}}

	//{{{ initSiteProperties() method
	/**
	 * Load site properties.
	 */
	private static void initSiteProperties()
	{
		// site properties are loaded as default properties, overwriting
		// jEdit's system properties

		String siteSettingsDirectory = MiscUtilities.constructPath(
			jEditHome, "properties");
		File siteSettings = new File(siteSettingsDirectory);

		if (!(siteSettings.exists() && siteSettings.isDirectory()))
			return;

		String[] snippets = siteSettings.list();
		if (snippets == null)
			return;

		Arrays.sort(snippets,
			new StandardUtilities.StringCompare<String>(true));

		for (String snippet : snippets)
		{
			if (!snippet.toLowerCase().endsWith(".props"))
				continue;

			try
			{
				String path = MiscUtilities.constructPath(siteSettingsDirectory, snippet);
				Log.log(Log.DEBUG, jEdit.class, "Loading site snippet: " + path);

				propMgr.loadSiteProps(new FileInputStream(new File(path)));
			}
			catch (FileNotFoundException fnf)
			{
				Log.log(Log.DEBUG, jEdit.class, fnf);
			}
			catch (IOException e)
			{
				Log.log(Log.ERROR, jEdit.class, "Cannot load site snippet " + snippet);
				Log.log(Log.ERROR, jEdit.class, e);
			}
		}
	} //}}}

	//{{{ initResources() method
	private static void initResources()
	{
		builtInActionSet = new ActionSet(null,null,null,
			jEdit.class.getResource("actions.xml"));
		builtInActionSet.setLabel(getProperty("action-set.jEdit"));
		builtInActionSet.load();

		actionContext.addActionSet(builtInActionSet);

		DockableWindowFactory.getInstance()
			.loadDockableWindows(null,
			jEdit.class.getResource("dockables.xml"),
			null);

		ServiceManager.loadServices(null,
			jEdit.class.getResource("services.xml"),
			null);
	} //}}}

	//{{{ initPlugins() method
	/**
	 * Loads plugins.
	 */
	private static void initPlugins()
	{
		if(jEditHome != null)
		{
			addPluginJARsFromDirectory(MiscUtilities.constructPath(
				jEditHome,"jars"));
		}

		if(settingsDirectory != null)
		{
			File jarsDirectory = new File(settingsDirectory,"jars");
			if(!jarsDirectory.exists())
				jarsDirectory.mkdir();
			addPluginJARsFromDirectory(jarsDirectory.getPath());
		}

		PluginJAR[] jars = getPluginJARs();
		for (PluginJAR jar : jars)
			jar.checkDependencies();
	} //}}}

	//{{{ initUserProperties() method
	/**
	 * Loads user properties.
	 */
	private static void initUserProperties()
	{
		if(settingsDirectory != null)
		{
			File file = new File(MiscUtilities.constructPath(
				settingsDirectory,"properties"));
			propsModTime = file.lastModified();

			try
			{
				propMgr.loadUserProps(
					new FileInputStream(file));
			}
			catch(FileNotFoundException fnf)
			{
				//Log.log(Log.DEBUG,jEdit.class,fnf);
			}
			catch(Exception e)
			{
				Log.log(Log.ERROR,jEdit.class,e);
			}
		}
	} //}}}

	//{{{ initLocalizationProperties() method
	/**
	 * Loads localization property file(s).
	 */
	private static void initLocalizationProperties(boolean reinit)
	{
		String language = getCurrentLanguage();
		if (!reinit && "en".equals(language))
		{
			// no need to load english as localization property as it always loaded as default language
			return;
		}
		Reader langResource = null;
		try
		{
			langResource = getResourceAsUTF8Text("/org/jedit/localization/jedit_" + language + ".props");
			propMgr.loadLocalizationProps(langResource);
		}
		catch (IOException e)
		{
			if (getBooleanProperty("lang.usedefaultlocale"))
			{
				// if it is the default locale, it is not an error
				Log.log(Log.ERROR, jEdit.class, "Unable to load language", e);
			}
		}
		finally
		{
			IOUtilities.closeQuietly((Closeable)langResource);
		}
	} //}}}

	//{{{ fontStyleToString() method
	private static String fontStyleToString(int style)
	{
		if(style == 0)
			return "PLAIN";
		else if(style == Font.BOLD)
			return "BOLD";
		else if(style == Font.ITALIC)
			return "ITALIC";
		else if(style == (Font.BOLD | Font.ITALIC))
			return "BOLDITALIC";
		else
			throw new RuntimeException("Invalid style: " + style);
	} //}}}

	//{{{ fontToString() method
	private static String fontToString(Font font)
	{
		return font.getFamily()
			+ '-'
			+ fontStyleToString(font.getStyle())
			+ '-'
			+ font.getSize();
	} //}}}

	//{{{ initPLAF() method
	/**
	 * Sets the Swing look and feel.
	 */
	private static void initPLAF()
	{
		String lf = getProperty("lookAndFeel");
		final String sLf = getPLAFClassName(lf);
		String sLfOld = null;
		String sLfNew = null;
		LookAndFeel lfOld = UIManager.getLookAndFeel();
		if (lfOld != null)
			sLfOld = lfOld.getClass().getName();

		Font primaryFont = jEdit.getFontProperty(
			"metal.primary.font");
		if(primaryFont != null)
		{
			String primaryFontString =
				fontToString(primaryFont);

			System.getProperties().put(
				"swing.plaf.metal.controlFont",
				primaryFontString);
			System.getProperties().put(
				"swing.plaf.metal.menuFont",
				primaryFontString);
		}

		Font secondaryFont = jEdit.getFontProperty(
			"metal.secondary.font");
		if(secondaryFont != null)
		{
			String secondaryFontString =
				fontToString(secondaryFont);

			System.getProperties().put(
				"swing.plaf.metal.systemFont",
				secondaryFontString);
			System.getProperties().put(
				"swing.plaf.metal.userFont",
				secondaryFontString);
		}

		// Though the cause is not known, this must precede
		// UIManager.setLookAndFeel(), so that menu bar
		// interaction by ALT key interacts with swing.JMenuBar
		// (which uses L&F) instead of awt.MenuBar which we
		// don't use (and doesn't use L&F).
		// The difference of the behavior was seen on Sun JRE
		// 6u16 on Windows XP and Windows L&F.
		KeyboardFocusManager.setCurrentKeyboardFocusManager(
			new MyFocusManager());

		// A couple of issues here -- (these are fixed)
		// First, setLookAndFeel must be called on the EDT. On initial start
		// up this isn't a problem, but initPLAF is called on propertiesChanged,
		// which can happen a lot.
		// Second, this will fail to load the look and feel as set in the
		// LookAndFeel plugin on initial start up because the plugins haven't
		// been loaded yet.
		if (EventQueue.isDispatchThread())
		{
			try
			{
				UIManager.setLookAndFeel(sLf);
			}
			catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e)
			{
				// ignored, there really isn't anything to do and this may be
				// bogus, the lnf may be from the Look And Feel plugin
			}
		}
		else
		{
			try
			{
				EventQueue.invokeAndWait(() ->
					{
						try
						{
							UIManager.setLookAndFeel(sLf);
						}
						catch (ClassNotFoundException | IllegalAccessException | InstantiationException | UnsupportedLookAndFeelException e)
						{
							// same as above, there really isn't anything to do and this may be
							// bogus, the lnf may be from the Look And Feel plugin
						}
					}
				);
			}
			catch (InterruptedException | InvocationTargetException e)
			{
				// don't worry about this one either
			}
		}

		LookAndFeel lfNew = UIManager.getLookAndFeel();
		if (lfNew != null)
			sLfNew = lfNew.getClass().getName();
			Log.log(Log.DEBUG, jEdit.class,
				"initPLAF " +
				(EventQueue.isDispatchThread() ? "edt"
				                               : "non-edt") +
				" old=" + sLfOld +
				" requested=" + lf +
				" new=" + sLfNew );
		if (!sLf.equals(sLfNew))
			Log.log(Log.WARNING, jEdit.class,
				"initPLAF failed to set requested l&f " + lf);

		UIDefaults defaults = UIManager.getDefaults();

		// give all Swing components our colors
		if(jEdit.getBooleanProperty("textColors"))
		{
			Color background = new javax.swing.plaf.ColorUIResource(
				jEdit.getColorProperty("view.bgColor"));
			Color foreground = new javax.swing.plaf.ColorUIResource(
				jEdit.getColorProperty("view.fgColor"));
			Color caretColor = new javax.swing.plaf.ColorUIResource(
				jEdit.getColorProperty("view.caretColor"));
			Color selectionColor = new javax.swing.plaf.ColorUIResource(
				jEdit.getColorProperty("view.selectionColor"));

			String[] prefixes = { "PasswordField", "TextField", "TextArea", "List", "Table" };
			for (String prefix : prefixes)
			{
				defaults.put(prefix + ".foreground", foreground);
				defaults.put(prefix + ".background", background);
				defaults.put(prefix + ".disabledForeground", foreground);
				defaults.put(prefix + ".disabledBackground", background);
				defaults.put(prefix + ".caretForeground", caretColor);
				defaults.put(prefix + ".selectionForeground", foreground);
				defaults.put(prefix + ".selectionBackground", selectionColor);
			}

			defaults.put("ComboBox.foreground",foreground);
			defaults.put("ComboBox.background",background);
			defaults.put("ComboBox.disabledForeground",foreground);
			defaults.put("ComboBox.disabledBackground",background);
			defaults.put("ComboBox.selectedForeground",foreground);
			defaults.put("ComboBox.selectedBackground",selectionColor);

			defaults.put("Tree.background",background);
			defaults.put("Tree.foreground",foreground);
			defaults.put("Tree.textBackground",background);
			defaults.put("Tree.textForeground",foreground);
			defaults.put("Tree.selectionForeground",foreground);
			defaults.put("Tree.selectionBackground",selectionColor);
		}

		defaults.remove("SplitPane.border");
		defaults.remove("SplitPaneDivider.border");

		defaults.put("Tree.rowHeight", 0);

		JFrame.setDefaultLookAndFeelDecorated(
			getBooleanProperty("decorate.frames"));
		JDialog.setDefaultLookAndFeelDecorated(
			getBooleanProperty("decorate.dialogs"));

		if (isStartupDone())
		{
			int iWindow = 0;
			for (Window window : Window.getWindows())
			{
				try
				{
					SwingUtilities.updateComponentTreeUI(window);
				}
				catch(Exception e)
				{
					Log.log(Log.ERROR, jEdit.class,
						"Window " + iWindow
						+ ": " + window, e);
					break;
				}
				iWindow++;
			}
		}

	} //}}}

	@Nonnull
	private static String getPLAFClassName(@Nullable String lf)
	{
		if (lf != null && !lf.isEmpty())
		{
			return lf;
		}
		else if(OperatingSystem.isMacOS())
		{
			return UIManager.getSystemLookAndFeelClassName();
		}
		else
		{
			return UIManager.getCrossPlatformLookAndFeelClassName();
		}
	}

	//{{{ getNextUntitledBufferId() method
	public static int getNextUntitledBufferId()
	{
		return bufferManager.getNextUntitledBufferId();
	} //}}}

	//{{{ runStartupScripts() method
	/**
	 * Runs scripts in a directory.
	 */
	private static void runStartupScripts(File directory)
	{
		if (!directory.isDirectory())
			return;

		File[] snippets = directory.listFiles();
		if (snippets == null)
			return;

		Arrays.sort(snippets,
			new StandardUtilities.StringCompare<>(true));

		/*
		 * Force the default encoding to UTF-8 temporarily.
		 * The shipped scripts use that encoding, so we need
		 * to make sure we can load them correctly. If users
		 * want to write script with a different encoding,
		 * they can use buffer-local properties on the
		 * script to set it.
		 */
		String defaultEncoding = getProperty("buffer.encoding");
		setProperty("buffer.encoding", "UTF-8");

		for (File snippet : snippets)
		{
			Macros.Handler handler = Macros.getHandlerForPathName(snippet.getPath());
			if (handler == null)
				continue;

			try
			{
				Macros.Macro newMacro = handler.createMacro(snippet.getName(), snippet.getPath());
				handler.runMacro(null, newMacro, false);
			}
			catch (Exception e)
			{
				Log.log(Log.ERROR, jEdit.class, e);
			}
		}

		setProperty("buffer.encoding", defaultEncoding);
	} //}}}

	//{{{ initProxy() method
	private static void initProxy()
	{
		boolean socksEnabled = jEdit.getBooleanProperty("firewall.socks.enabled");
		if(!socksEnabled)
		{
			Log.log(Log.DEBUG,jEdit.class,"SOCKS proxy disabled");
			System.getProperties().remove("socksProxyHost");
			System.getProperties().remove("socksProxyPort");
		}
		else
		{
			String socksHost = jEdit.getProperty("firewall.socks.host");
			if( socksHost != null )
			{
				System.setProperty("socksProxyHost", socksHost);
				Log.log(Log.DEBUG, jEdit.class,
					"SOCKS proxy enabled: " + socksHost);
			}

			String socksPort = jEdit.getProperty("firewall.socks.port");
			if(socksPort != null)
				System.setProperty("socksProxyPort", socksPort);
		}

		boolean httpEnabled = jEdit.getBooleanProperty("firewall.enabled");
		if (!httpEnabled)
		{
			Log.log(Log.DEBUG, jEdit.class, "HTTP proxy disabled");
			System.getProperties().remove("proxySet");
			System.getProperties().remove("proxyHost");
			System.getProperties().remove("proxyPort");
			System.getProperties().remove("http.proxyHost");
			System.getProperties().remove("http.proxyPort");
			System.getProperties().remove("http.nonProxyHosts");
			Authenticator.setDefault(null);
		}
		else
		{
			// set proxy host
			String host = jEdit.getProperty("firewall.host");
			if (host == null)
				return;

			System.setProperty("http.proxyHost", host);
			Log.log(Log.DEBUG, jEdit.class, "HTTP proxy enabled: " + host);
			// set proxy port
			String port = jEdit.getProperty("firewall.port");
			if (port != null)
				System.setProperty("http.proxyPort", port);

			// set non proxy hosts list
			String nonProxyHosts = jEdit.getProperty("firewall.nonProxyHosts");
			if (nonProxyHosts != null)
				System.setProperty("http.nonProxyHosts", nonProxyHosts);

			// set proxy authentication
			String username = jEdit.getProperty("firewall.user");
			String password = jEdit.getProperty("firewall.password");

			// null not supported?
			if(password == null)
				password = "";

			if(username == null || username.isEmpty())
			{
				Log.log(Log.DEBUG, jEdit.class, "HTTP proxy without user");
				Authenticator.setDefault(new FirewallAuthenticator(null));
			}
			else
			{
				Log.log(Log.DEBUG, jEdit.class, "HTTP proxy user: " + username);
				PasswordAuthentication pw = new PasswordAuthentication(
					username,password.toCharArray()
				);
				Authenticator.setDefault(new FirewallAuthenticator(pw));
			}
		}
	} //}}}

	//{{{ FirewallAuthenticator class
	static class FirewallAuthenticator extends Authenticator
	{
		PasswordAuthentication pw;

		FirewallAuthenticator(PasswordAuthentication pw)
		{
			this.pw = pw;
		}

		@Override
		protected PasswordAuthentication getPasswordAuthentication()
		{
			return pw;
		}
	} //}}}

	//{{{ finishStartup() method
	private static void finishStartup(final boolean gui, final boolean restore,
		final boolean newPlainView, final String userDir, final String[] args)
	{
		EventQueue.invokeLater(new Runnable()
		{
			@Override
			public void run()
			{
				int count = bufferManager.size();

				boolean restoreFiles = restore
					&& jEdit.getBooleanProperty("restore")
					&& (count == 0 ||
					jEdit.getBooleanProperty("restore.cli"));

				if(gui || count != 0)
				{
					View view;
					if (newPlainView)
						view = newView(null,null,true);
					else
						view = PerspectiveManager.loadPerspective(restoreFiles);

					if(view == null)
						view = newView(null,null);

					Buffer buffer;

					// Treat the elements of additionalFiles just like command-line arguments
					if (!additionalFiles.isEmpty())
					{
						String[] newArgs = new String[additionalFiles.size() + args.length];
						additionalFiles.copyInto(newArgs);
						System.arraycopy(args, 0, newArgs, additionalFiles.size(), args.length);
						// We need to pass view to openFiles, because when a file is openened via
						// the command line and is not the current buffer (because other buffers are
						// already openened) and '+line' command line argument is given, a view is
						// needed to scroll to the given line.
						buffer = openFiles(view,userDir,newArgs);
					}
					else
					{
						// See comment above in if part on passing view.
						buffer = openFiles(view,userDir,args);
					}

					if(buffer != null) {
						view.setBuffer(buffer);
					}
					view.toFront();
					
				}
				else
				{
					openFiles(null,userDir,args);
				}

				// Start I/O threads
				EditBus.send(new EditorStarted(null));

				VFSManager.start();

				// Start edit server
				if(server != null)
					server.start();

				GUIUtilities.hideSplashScreen();

				Log.log(Log.MESSAGE,jEdit.class,"Startup "
					+ "complete: "
					+ (System.currentTimeMillis() -
					   startupTime) + " ms");

				//{{{ Report any plugin errors
				if(pluginErrors != null)
				{
					showPluginErrorDialog();
				} //}}}

				startupDone.set(0, true);

				// in one case not a single AWT class will
				// have been touched (splash screen off +
				// -nogui -nobackground switches on command
				// line)
				Toolkit.getDefaultToolkit();
			}
		});
	} //}}}

	//{{{ showPluginErrorDialog() method
	private static void showPluginErrorDialog()
	{
		if(pluginErrors == null)
			return;

		String caption = getProperty(
			"plugin-error.caption" + (pluginErrors.size() == 1
			? "-1" : ""));

		Frame frame = (PluginManager.getInstance() == null
			? viewManager.getFirst()
			: PluginManager.getInstance());

		new ErrorListDialog(frame,
			getProperty("plugin-error.title"),
			caption,pluginErrors,true);
		pluginErrors = null;
	} //}}}

	//{{{ getNotLoadedPluginJARs() method
	private static void getNotLoadedPluginJARs(Collection<String> returnValue,
		String dir, String[] list)
	{
loop:
		for (String name : list)
		{
			if (!name.toLowerCase().endsWith(".jar"))
				continue loop;

			String path = MiscUtilities.constructPath(dir, name);

			for (int j = 0; j < jars.size(); j++)
			{
				PluginJAR jar = jars.elementAt(j);
				String jarPath = jar.getPath();

				if (path.equals(jarPath)
				    || name.equals(MiscUtilities.getFileName(jarPath)) && !new File(jarPath).exists())
					continue loop;
			}

			returnValue.add(path);
		}
	} //}}}

	//{{{ gotoMarker() method
	private static void gotoMarker(final View view, final Buffer buffer,
		final String marker)
	{
		AwtRunnableQueue.INSTANCE.runAfterIoTasks(new Runnable()
		{
			@Override
			public void run()
			{
				int pos;

				// Handle line number
				if(marker.startsWith("+line:"))
				{
					try
					{
						String arg = marker.substring(6);
						String[] lineCol = arg.split(",");
						int line, col;
						if(lineCol.length > 1)
						{
							line = parseInt(lineCol[0]);
							col = parseInt(lineCol[1]);
						}
						else
						{
							line = parseInt(marker.substring(6));
							col = 1;
						}
						pos = buffer.getLineStartOffset(line - 1) + (col - 1);
					}
					catch(Exception e)
					{
						return;
					}
				}
				// Handle marker
				else if(marker.startsWith("+marker:"))
				{
					if(marker.length() != 9)
						return;

					Marker m = buffer.getMarker(marker.charAt(8));
					if(m == null)
						return;
					pos = m.getPosition();
				}
				// Can't happen
				else
					throw new InternalError();

				if(view != null && view.getBuffer() == buffer)
				{
					view.getTextArea().setCaretPosition(pos);
					buffer.setIntegerProperty(Buffer.CARET,pos);
					buffer.setBooleanProperty(Buffer.CARET_POSITIONED,true);
				}
				else
				{
					buffer.setIntegerProperty(Buffer.CARET,pos);
					buffer.setBooleanProperty(Buffer.CARET_POSITIONED,true);
					buffer.unsetProperty(Buffer.SCROLL_VERT);
				}
			}
		});
	} //}}}

	//{{{ closeView() method
	/**
	 * closeView() used by exit().
	 */
	private static boolean closeView(View view, boolean callExit)
	{
		PerspectiveManager.setPerspectiveDirty(true);

		if(viewManager.getFirst() == viewManager.getLast() && callExit)
		{
			exit(view,false); /* exit does editor event & save */
			// Coming here means the request has been canceled.
			return false;
		}
		else
		{
			if (!view.confirmToCloseDirty())
				return false;

			// move the dirty untitled buffers to the next open view's current editpane bufferset (first or last)
			boolean moveUntitled = jEdit.getBooleanProperty("autosaveUntitled");
			if (moveUntitled && getBufferSetManager().getScope() != BufferSet.Scope.global) {
				View targetView;
				if (view.equals(viewManager.getFirst()))
				{
					targetView = viewManager.getLast();
				}
				else
				{
					targetView = viewManager.getFirst();
				}
				BufferSet bufferSet = targetView.getEditPane().getBufferSet();
				for (Buffer buffer : view.getBuffers()) {
					if ( buffer.isUntitled() && buffer.isDirty()) {
						bufferSet.addBuffer(buffer);
					}
				}
			}

			view.close();
			view.dispose();
			viewManager.remove(view);

			if(view == viewManager.getActiveViewInternal())
				viewManager.setActiveView(null);

			return true;
		}
	} //}}}

	//{{{ loadModeCatalog() method
	/**
	 * Loads a mode catalog file.
	 * @since jEdit 3.2pre2
	 */
	private static void loadModeCatalog(String path, boolean resource, final boolean userMode)
	{
		Log.log(Log.MESSAGE,jEdit.class,"Loading mode catalog file " + path);

		ModeCatalogHandler handler = new ModeCatalogHandler(
			MiscUtilities.getParentOfPath(path),resource)
		{
			@Override
			protected Mode instantiateMode(String modeName)
			{
				Mode mode = new JEditMode(modeName);
				mode.setUserMode(userMode);
				return mode;
			}
		};
		try
		{
			InputStream _in;
			if(resource)
				_in = jEdit.class.getResourceAsStream(path);
			else
				_in = new FileInputStream(path);
			XMLUtilities.parseXML(_in, handler);
		}
		catch(IOException e)
		{
			Log.log(Log.ERROR,jEdit.class,e);
		}
	} //}}}

	//{{{ initKeyBindings() method
	/**
	 * Loads all key bindings from the properties.
	 *
	 * @since 3.1pre1
	 */
	private static void initKeyBindings()
	{
		inputHandler.removeAllKeyBindings();

		ActionSet[] actionSets = getActionSets();
		for (ActionSet actionSet : actionSets)
			actionSet.initKeyBindings();
	} //}}}

	//{{{ composeBufferPropsFromHistory() method
	/**
	 * Compose buffer-local properties which can be got from history.
	 * @since 4.3pre10
	 */
	private static void composeBufferPropsFromHistory(Map<String, Object> props, String path)
	{
		BufferHistory.Entry entry = BufferHistory.getEntry(path);

		if(entry != null && saveCaret && props.get(Buffer.CARET) == null)
		{
			props.put(Buffer.CARET, entry.caret);
			 if(entry.selection != null)
			{
				// getSelection() converts from string to
				// Selection[]
				props.put(Buffer.SELECTION,entry.getSelection());
			}
		}

		if(entry != null && props.get(JEditBuffer.ENCODING) == null)
		{
			if(entry.encoding != null)
				props.put(JEditBuffer.ENCODING,entry.encoding);
		}

		if (entry != null && props.get("mode") == null)
		{
			if (entry.mode != null)
				props.put("mode", entry.mode);
		}
	} //}}}

	//}}}

	//{{{ MyFocusManager class
	private static class MyFocusManager extends DefaultKeyboardFocusManager
	{
		MyFocusManager()
		{
			setDefaultFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
		}

		@Override
		public boolean postProcessKeyEvent(KeyEvent evt)
		{
			if(!evt.isConsumed())
			{
				Component comp = (Component)evt.getSource();
				if(!comp.isShowing())
					return true;

				for(;;)
				{
					if(comp instanceof View)
					{
						((View)comp).getInputHandler().processKeyEvent(evt,
							View.VIEW, false);
						return true;
					}
					else if(comp == null || comp instanceof Window
						|| comp instanceof JEditTextArea)
					{
						if (comp instanceof PluginManager)
						{
							evt.setSource(comp);
							((PluginManager)comp).processKeyEvents(evt);
						}
						break;
					}
					else
						comp = comp.getParent();
				}
			}

			return super.postProcessKeyEvent(evt);
		}
	} //}}}

	//{{{ JEditPropertyManager class
	public static class JEditPropertyManager implements IPropertyManager
	{
		@Override
		public String getProperty(String name)
		{
			return jEdit.getProperty(name);
		}
	} //}}}

	//{{{ DockingLayoutSetter class
	private static class DockingLayoutSetter implements Runnable
	{
		private final View view;
		private final ViewConfig config;
		private final int startupDoneIndex;

		DockingLayoutSetter(View view, ViewConfig config, int startupDoneIndex)
		{
			this.view = view;
			this.config = config;
			this.startupDoneIndex = startupDoneIndex;
		}

		@Override
		public void run()
		{
			DockableWindowManager wm = view.getDockableWindowManager();
			wm.setDockingLayout(config.docking);
			startupDone.set(startupDoneIndex, true);
		}
	} //}}}

	@Override
	public void run() {
		
		String[] args = new String[1];
		
		mainApplicationStart(args);
	}
}
