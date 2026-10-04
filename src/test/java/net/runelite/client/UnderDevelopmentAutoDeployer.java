package net.runelite.client;

import com.google.common.reflect.ClassPath;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.UnderDevelopment;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-deploys every {@code @UnderDevelopment} plugin into the running debug
 * client via the agent server's {@code /scripts/deploy} endpoint, so they come
 * up hot-reloadable on every launch with no manual step.
 * <p>
 * Flow (run on a daemon thread kicked off from {@link Microbot#main}):
 * <ol>
 *   <li>Scan the classpath under {@code net.runelite.client.plugins.custom} for
 *       Plugin classes annotated {@link UnderDevelopment} (those are skipped by
 *       the client's startup sweeps and so are free to be deployed dynamically).</li>
 *   <li>Wait for the agent server to answer.</li>
 *   <li>POST {@code /scripts/deploy} for each, compiling from its source dir.</li>
 * </ol>
 * After this, edit source and recompile-in-place with
 * {@code scripts/reload-plugin.sh <pluginDir>} — no client restart.
 * <p>
 * Disable with {@code -Dmicrobot.hotreload.autodeploy=false}. Override the
 * source root with {@code -Dmicrobot.hotreload.sourceRoot=/abs/path} (defaults
 * to {@code <working-dir>/src/main/java}, which is the Hub project when launched
 * via the {@code runDebug} Gradle task).
 */
final class UnderDevelopmentAutoDeployer
{
	private static final String SCAN_PACKAGE = "net.runelite.client.plugins.custom";
	private static final String HOST = System.getProperty("microbot.hotreload.host", "127.0.0.1");
	private static final int PORT = Integer.getInteger("microbot.hotreload.port", 8081);
	private static final String BASE = "http://" + HOST + ":" + PORT;

	private UnderDevelopmentAutoDeployer()
	{
	}

	/** Spawns a daemon thread that deploys all @UnderDevelopment plugins once the server is up. */
	static void launch()
	{
		if (!Boolean.parseBoolean(System.getProperty("microbot.hotreload.autodeploy", "true")))
		{
			return;
		}

		Thread t = new Thread(UnderDevelopmentAutoDeployer::run, "underdev-autodeploy");
		t.setDaemon(true);
		t.start();
	}

	private static void run()
	{
		List<Class<?>> targets;
		try
		{
			targets = scan();
		}
		catch (IOException e)
		{
			System.err.println("[autodeploy] classpath scan failed: " + e.getMessage());
			return;
		}

		if (targets.isEmpty())
		{
			return;
		}

		System.out.println("[autodeploy] found " + targets.size() + " @UnderDevelopment plugin(s); waiting for agent server...");
		if (!waitForServer(120))
		{
			System.err.println("[autodeploy] agent server never came up at " + BASE + "; skipping auto-deploy.");
			return;
		}

		String sourceRoot = System.getProperty("microbot.hotreload.sourceRoot",
			Paths.get(System.getProperty("user.dir"), "src", "main", "java").toString());
		String token = readToken();

		for (Class<?> clazz : targets)
		{
			String pkg = clazz.getPackage().getName();
			String name = pkg.substring(pkg.lastIndexOf('.') + 1);
			Path sourceDir = Paths.get(sourceRoot, pkg.split("\\."));

			if (!Files.isDirectory(sourceDir))
			{
				System.err.println("[autodeploy] " + name + ": source dir not found: " + sourceDir);
				continue;
			}

			try
			{
				deploy(name, sourceDir.toString(), token);
				System.out.println("[autodeploy] deployed '" + name + "' from " + sourceDir);
			}
			catch (IOException e)
			{
				System.err.println("[autodeploy] deploy '" + name + "' failed: " + e.getMessage());
			}
		}
	}

	private static List<Class<?>> scan() throws IOException
	{
		List<Class<?>> found = new ArrayList<>();
		ClassLoader cl = UnderDevelopmentAutoDeployer.class.getClassLoader();
		for (ClassPath.ClassInfo ci : ClassPath.from(cl).getTopLevelClassesRecursive(SCAN_PACKAGE))
		{
			try
			{
				Class<?> clazz = ci.load();
				if (Plugin.class.isAssignableFrom(clazz)
					&& clazz.isAnnotationPresent(PluginDescriptor.class)
					&& clazz.isAnnotationPresent(UnderDevelopment.class))
				{
					found.add(clazz);
				}
			}
			catch (Throwable ignored)
			{
				// unloadable class on the scan path — ignore
			}
		}
		return found;
	}

	private static boolean waitForServer(int timeoutSeconds)
	{
		long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
		while (System.currentTimeMillis() < deadline)
		{
			try
			{
				HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "/scripts/deploy").openConnection();
				conn.setConnectTimeout(2000);
				conn.setReadTimeout(2000);
				conn.setRequestMethod("GET");
				addToken(conn, readToken());
				int code = conn.getResponseCode();
				conn.disconnect();
				if (code > 0 && code < 500)
				{
					return true;
				}
			}
			catch (IOException ignored)
			{
				// not up yet
			}
			try
			{
				Thread.sleep(1000);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	private static void deploy(String name, String sourcePath, String token) throws IOException
	{
		// start=false: register the plugin only. Activation stays a manual action.
		String body = "{\"name\":\"" + name + "\",\"sourcePath\":\"" + jsonEscape(sourcePath) + "\",\"start\":false}";
		HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "/scripts/deploy").openConnection();
		conn.setConnectTimeout(5000);
		conn.setReadTimeout(60000);
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setRequestProperty("Content-Type", "application/json");
		addToken(conn, token);
		try (OutputStream os = conn.getOutputStream())
		{
			os.write(body.getBytes(StandardCharsets.UTF_8));
		}
		int code = conn.getResponseCode();
		if (code != 200)
		{
			String msg = readStream(conn, true);
			throw new IOException("HTTP " + code + ": " + msg);
		}
		conn.disconnect();
	}

	private static void addToken(HttpURLConnection conn, String token)
	{
		if (token != null && !token.isEmpty())
		{
			conn.setRequestProperty("X-Agent-Token", token);
		}
	}

	private static String readToken()
	{
		String env = System.getenv("MICROBOT_TOKEN");
		if (env != null && !env.isEmpty())
		{
			return env;
		}
		Path tokenFile = Paths.get(System.getProperty("user.home"), ".runelite", ".agent-token");
		try
		{
			if (Files.isReadable(tokenFile))
			{
				return new String(Files.readAllBytes(tokenFile), StandardCharsets.UTF_8).trim();
			}
		}
		catch (IOException ignored)
		{
		}
		return null;
	}

	private static String readStream(HttpURLConnection conn, boolean error) throws IOException
	{
		java.io.InputStream in = error ? conn.getErrorStream() : conn.getInputStream();
		if (in == null)
		{
			return "";
		}
		return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
	}

	private static String jsonEscape(String s)
	{
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
