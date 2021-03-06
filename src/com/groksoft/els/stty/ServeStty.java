package com.groksoft.els.stty;

import com.groksoft.els.Configuration;
import com.groksoft.els.Main;
import com.groksoft.els.MungerException;
import com.groksoft.els.Utils;
import com.groksoft.els.repository.Repository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * Manage all connections and enforce limits.
 * <p>
 * The ServeStty class is a subclass of Thread. It keeps a list of all
 * connections, and enforces the maximum connection limit.
 * <p>
 * Each connection uses a separate thread.
 * <p>
 * There is one ServeStty for the entire server.
 * <p>
 * The ServeStty uses another thread to remove dead connections from the
 * allConnections list.
 */
public class ServeStty extends Thread
{
    private transient Logger logger = LogManager.getLogger("applog");

    /**
     * The list of all service connections
     */
    private Vector<Connection> allConnections;
    /**
     * The single instance of this class
     */
    private static ServeStty instance = null;
    /**
     * The maximum connections allowed for this entire server instance
     */
    protected int maxConnections;
    /**
     * Count of total connections since started
     */
    private int totalConnections = 0;
    /**
     * Flag used to determine when to stop listening
     */
    private boolean _stop = false;

    private Hashtable<String, Listener> allSessions;
    private ThreadGroup allSessionThreads;

    private Configuration cfg;
    private Main.Context context;
    private int listenPort;
    private boolean primaryServers;

    /**
     * Instantiates the ServeStty object and set it as a daemon so the Java
     * Virtual Machine does not wait for it to exit.
     */
    public ServeStty(ThreadGroup aGroup, int aMaxConnections, Configuration config, Main.Context ctxt, boolean primaryServers)
    {
        // instantiate this object in the specified thread group to
        // enforce the specified maximum connections limitation.
        super(aGroup, "ServeStty");
        instance = this;
        instance.cfg = config;
        instance.context = ctxt;
        instance.primaryServers = primaryServers;

        // make it a daemon so the JVM does not wait for it to exit
        this.setDaemon(true);
        this.setMaxConnections(aMaxConnections);
        this.allConnections = new Vector<Connection>();
        this.allSessions = new Hashtable<String, Listener>();
        this.allSessionThreads = aGroup;
    } // constructor

    /**
     * Add a connection for a service.
     * <p>
     * Responds to a connection request. The maximum connection limit is
     * checked. If the limit has not been exceeded the new connection is added
     * to allConnections, and a thread is started to service the request.
     */
    public synchronized void addConnection(Socket aSocket) throws MungerException
    {
        // check for maximum connections
        if (allConnections.size() >= maxConnections)
        {
            // maximum connections exceeded - try to tell user
            try
            {
                PrintWriter clientOut = new PrintWriter(aSocket.getOutputStream());
                clientOut.println("Connection request denied; maximum users exceeded");
                clientOut.flush();

                // log it
                logger.info("Maximum connections (" + maxConnections + ") exceeded");
                logger.info("Connection refused from " + aSocket.getInetAddress().getHostAddress() + ":" + aSocket.getPort());

                // close the connection
                aSocket.close();
            } catch (IOException e)
            {
                logger.info(e);
            }
        } else
        // if limit has not been reached
        {
            // create a connection thread for this request
            Connection theConnection;
            if (cfg.isPublisherListener())
            {
                theConnection = new Connection(aSocket, new com.groksoft.els.stty.publisher.Daemon(cfg, context, context.publisherRepo, context.subscriberRepo));
            } else if (cfg.isSubscriberListener() || cfg.isSubscriberTerminal())
            {
                theConnection = new Connection(aSocket, new com.groksoft.els.stty.subscriber.Daemon(cfg, context, context.subscriberRepo, context.publisherRepo));
            } else
            {
                throw new MungerException("FATAL: Unknown connection type");
            }
            allConnections.add(theConnection);

            // log it
            logger.info((cfg.isPublisherListener() ? "Publisher" : "Subscriber") + " daemon opened " + aSocket.getInetAddress().getHostAddress() + ":" + aSocket.getPort());

            // start the connection thread
            theConnection.start();
            ++totalConnections;
        }
    }

    /**
     * Start a session listener
     */
    public void startListening(Repository listenerRepo) throws Exception
    {
        if (listenerRepo != null &&
                listenerRepo.getLibraryData() != null &&
                listenerRepo.getLibraryData().libraries != null &&
                listenerRepo.getLibraryData().libraries.listen != null)
        {
            startServer(listenerRepo.getLibraryData().libraries.listen);
        } else
        {
            throw new MungerException("cannot get site from -r specified remote library");
        }
    }

    /**
     * End a client connection.
     * <p>
     * Notifies the ServeStty that this connection has been closed. Called
     * from the run() method of the Connection thread created by addConnection()
     * when the connection is closed for any reason.
     *
     * @see Connection
     */
    public synchronized void endConnection()
    {
        // notify the ServeStty thread that this connection has closed
        this.notify();
    }

    /**
     * Get this instance.
     */
    public static ServeStty getInstance()
    {
        return instance;
    }

    /**
     * Get the connections Vector
     */
    public Vector getAllConnections()
    {
        return this.allConnections;
    }

    /**
     * Set or change the maximum number of connections allowed for this server.
     */
    public synchronized void setMaxConnections(int aMax)
    {
        maxConnections = aMax;
    }

    /**
     * Dump statistics of connections.
     */
    public synchronized String dumpStatistics()
    {
        String data = "Listening on: " + listenPort + "\r\n" +
                "Active connections: " + allConnections.size() + "\r\n";
        for (int index = 0; index < allConnections.size(); ++index)
        {
            Connection c = (Connection) allConnections.elementAt(index);
            data += "  " + c.service.getName() + " to " + c.socket.getInetAddress().getHostAddress() + ":" + c.socket.getPort() + "\r\n";
        }

        // dump connection counts
        data += "  Total connections since started: " + totalConnections + "\r\n";
        data += "  Maximum allowed connections: " + maxConnections + "\r\n";

        return data;
    }

    /**
     * Politely request the listener to stop.
     */
    public void requestStop()
    {
        this._stop = true;
        for (int index = 0; index < allConnections.size(); ++index)
        {
            // stop live connections
            Connection c = (Connection) allConnections.elementAt(index);
            if (c.isAlive())
            {
                c.getConsole().requestStop();
            }
        }
        this.interrupt();
    }

    /**
     * Thread used to clean-up dead connections.
     * <p>
     * Waits to be notified of a closed connection via a call to the
     * endConnection() method, then scans all current connections for any that
     * are dead. Each dead connection is removed from the allConnections list.
     */
    public void run()
    {
        // log it
        logger.info("Starting ServeStty server for up to " + maxConnections + " incoming connections");
        while (_stop == false)
        {
            for (int index = 0; index < allConnections.size(); ++index)
            {
                // remove dead connections
                Connection c = (Connection) allConnections.elementAt(index);
                if (!c.isAlive())
                {
                    allConnections.removeElementAt(index);
                    logger.info(c.service.getName() + " closed " + c.socket.getInetAddress().getHostAddress() + ":" + c.socket.getPort() + " port " + c.socket.getLocalPort());
                }
            }

            // wait for notify of closed connection
            try
            {
                synchronized (this)
                {
                    this.wait();
                }
            } catch (InterruptedException e)
            {
                logger.info("ServeStty interrupted, stop=" + ((_stop) ? "true" : "false"));
            }
        } // while (true)
        logger.info("Stopped ServeStty");
    }

    protected void addListener(String host, int aPort) throws Exception
    {
        //Integer key = new Integer(aPort);   // hashtable key

        // do not allow duplicate port assignments
        if (allSessions.get("Listener:" + host + ":" + aPort) != null)
            throw new IllegalArgumentException("Port " + aPort + " already in use");

        // create a listener on the port
        Listener listener = new Listener(allSessionThreads, host, aPort, cfg);

        // put it in the hashtable
        allSessions.put("Listener:" + host + ":" + aPort, listener);

        // log it
        logger.info("ServeStty server is listening on: " + (host == null ? "localhost" : listener.getInetAddr()) + ":" + aPort);

        // fire it up
        listener.start();
    }

    public void startServer(String listen) throws Exception
    {
        String host = Utils.parseHost(listen);
        if (host == null || host.isEmpty())
        {
            host = null;
            logger.info("Host not defined, using default: localhost");
        }
        listenPort = Utils.getPort(listen) + ((primaryServers) ? 0 : 2);
        if (listenPort > 0)
        {
            this.start();
            addListener(host, listenPort);
        }
        if (listenPort < 1)
        {
            logger.info("ServeStty is disabled");
        }
    }

    public void stopServer()
    {
        if (allSessions != null)
        {
            logger.info("Stopping all Sessions");
            Enumeration keys = allSessions.keys();
            while (keys.hasMoreElements())
            {
                //Integer port = (Integer)keys.nextElement();
                Connection conn = (Connection) keys.nextElement();
                Socket sock = conn.getSocket();
                Integer port = sock.getPort();
                Listener listener = (Listener) allSessions.get(port);
                if (listener != null)
                {
                    listener.requestStop();
                }
            }
            this.requestStop();
        } else
        {
            logger.info("nothing to stop");
        }
    }


} // ServeStty
