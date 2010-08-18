package ibis.hadoop;

import ibis.ipl.Credentials;
import ibis.ipl.Ibis;
import ibis.ipl.IbisCapabilities;
import ibis.ipl.IbisFactory;
import ibis.ipl.IbisIdentifier;
import ibis.ipl.MessageUpcall;
import ibis.ipl.PortType;
import ibis.ipl.ReadMessage;
import ibis.ipl.ReceivePort;
import ibis.ipl.ReceivePortIdentifier;
import ibis.ipl.RegistryEventHandler;
import ibis.ipl.SendPort;
import ibis.ipl.WriteMessage;
import ibis.ipl.support.Client;
import ibis.smartsockets.virtual.VirtualSocketAddress;
import ibis.smartsockets.virtual.VirtualSocketFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Properties;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobTracker;
import org.apache.hadoop.mapred.TaskTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a node in an Hadoop cluster. Will automatically create a
 * namenode/jobtracker on one of the nodes.
 * 
 * @author niels
 * 
 */
public class HadoopNode {

	public static final int HDFS_PORT = 6000;
	public static final int MAPREDUCE_PORT = 6001;

	private static final Logger logger = LoggerFactory
			.getLogger(HadoopNode.class);

	public static final IbisCapabilities ibisCapabilities = new IbisCapabilities(
			IbisCapabilities.ELECTIONS_STRICT);

	private final Ibis ibis;

	private static class Shutdown extends Thread {
		private final HadoopNode node;

		Shutdown(HadoopNode node) {
			this.node = node;
		}

		public void run() {
			node.end();
		}
	}

	@SuppressWarnings("deprecation")
	public HadoopNode(boolean isWorker, File tmp) throws Exception {

		// smartsockets init, via ipl support mechanism
		// Client client = Client.getOrCreateClient("hadoop", System
		// .getProperties(), -1);
		// VirtualSocketFactory socketFactory = client.getFactory();

		String localAddress = InetAddress.getLocalHost().getHostAddress();

		ibis = IbisFactory.createIbis(ibisCapabilities, null, true, null, null,
				localAddress, new PortType[0]);

		IbisIdentifier frontend;
		if (isWorker) {
			// do not try to win election
			frontend = ibis.registry().getElectionResult("frontend");
		} else {
			// try to win election
			frontend = ibis.registry().elect("frontend");
		}

		// set some system properties normally set by the hadoop script
		System.setProperty("hadoop.log.dir", tmp.getAbsolutePath()
				+ File.separator + "logs");
		System.setProperty("hadoop.log.file", "hadoop.log");
		System.setProperty("hadoop.home.dir", ".");
		System.setProperty("hadoop.id.str", InetAddress.getLocalHost()
				.getCanonicalHostName());
		System.setProperty("hadoop.policy.file", "hadoop-policy.xml");

		Configuration configuration = new Configuration(true);

		configuration.set("hadoop.tmp.dir", tmp.getAbsolutePath());
		configuration.set("fs.default.name", "hdfs://" + frontend.tagAsString()
				+ ":" + HDFS_PORT);
		configuration.set("mapred.job.tracker", frontend.tagAsString() + ":"
				+ MAPREDUCE_PORT);

		JobConf jobConf = new JobConf(configuration);

		if (frontend.equals(ibis.identifier())) {
			logger.info("Hadoop Server running on " + localAddress);

			// format filesystem
			NameNode.format(configuration);

			NameNode namenode = NameNode.createNameNode(null, configuration);

			final JobTracker jobTracker = JobTracker.startTracker(jobConf);

			Thread thread = new Thread() {

				public void run() {
					try {
						jobTracker.offerService();
					} catch (Exception e) {
						logger.error("Exception in jobtracker", e);
					}
				}
			};
			thread.setDaemon(true);
			thread.start();

			logger.info("Server initialized");
		} else {
			logger.info("Hadoop Worker running on " + localAddress);

			String serverVirtualSocketAddress = frontend.tagAsString();

			// clean tmp filesystem

			delete(new File(tmp, "dfs"));

			DataNode datanode = DataNode.createDataNode(null, configuration);

			TaskTracker taskTracker = new TaskTracker(jobConf);

			// start task tracker
			Thread thread = new Thread(taskTracker);
			thread.setDaemon(true);
			thread.start();

			logger.info("Worker initialized");
		}
	}

	// recursive delete
	private static void delete(File file) {
		if (file.isDirectory()) {
			for (File child : file.listFiles()) {
				delete(child);
			}
		} else {
			file.delete();
		}
	}

	public void end() {
		try {
			ibis.end();
		} catch (Exception e) {
			logger.error("Error on ending ibis", e);
		}
	}

	private void waitUntilFinished() {
		try {
			int read = 0;

			while (read != -1) {
				read = System.in.read();
			}
		} catch (IOException e) {
			// IGNORE
		}
	}

	/**
	 * @param args
	 *            arguments
	 */
	public static void main(String[] args) {
		HadoopNode node = null;

		boolean worker = false;

		File tmp = new File(System.getProperty("java.io.tmpdir")
				+ File.separator + "hadoop-tmp");

		for (int i = 0; i < args.length; i++) {
			if (args[i].equalsIgnoreCase("--worker")
					|| args[i].equalsIgnoreCase("-w")) {
				worker = true;
			} else if (args[i].equalsIgnoreCase("--tmp-dir")
					|| args[i].equalsIgnoreCase("-t")) {
				i++;
				tmp = new File(args[i]);
			} else {
				System.err
						.println("cannot start HadoopNode, unknown commandline parameter: "
								+ args[i]);
				System.exit(1);
			}
		}

		try {
			node = new HadoopNode(worker, tmp);
		} catch (Exception e) {
			System.err.println("Error on starting hadoop node");
			e.printStackTrace(System.err);
			System.exit(1);
		}

		System.err.println("Hadoop node started");

		// register shutdown hook
		try {
			Runtime.getRuntime().addShutdownHook(new Shutdown(node));
		} catch (Exception e) {
			System.err.println("warning: could not registry shutdown hook");
		}
	}

}
