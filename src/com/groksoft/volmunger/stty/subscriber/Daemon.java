package com.groksoft.volmunger.stty.subscriber;

import com.groksoft.volmunger.Configuration;
import com.groksoft.volmunger.MungerException;
import com.groksoft.volmunger.Utils;
import com.groksoft.volmunger.repository.Library;
import com.groksoft.volmunger.repository.Repository;
import com.groksoft.volmunger.stty.DaemonBase;
import com.groksoft.volmunger.stty.ServeStty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.StringTokenizer;

/**
 * Subscriber Daemon service.
 * <p>
 * The Daemon service is the command interface used to communicate between
 * the endpoints.
 */
public class Daemon extends DaemonBase
{
    protected static Logger logger = LogManager.getLogger("applog");

    private boolean isTerminal = false;

    /**
     * Instantiate the Daemon service
     *
     * @param config
     * @param pubRepo
     * @param subRepo
     */

    public Daemon(Configuration config, Repository pubRepo, Repository subRepo)
    {
        super(config, pubRepo, subRepo);
    } // constructor


    /**
     * Dump statistics from all available internal sources.
     */
    public synchronized String dumpStatistics()
    {
        String data = "\r\nConsole currently connected: " + ((connected) ? "true" : "false") + "\r\n";
        data += "  Connected on port: " + port + "\r\n";
        data += "  Connected to: " + address + "\r\n";
        return data;
    } // dumpStatistics

    /**
     * Get the short name of the service.
     *
     * @return Short name of this service.
     */
    public String getName()
    {
        return "Daemon";
    } // getName

    public boolean handshake()
    {
        boolean valid = false;
        try
        {
            Utils.write(out, subscriberKey, "HELO");

            String input = Utils.read(in, subscriberKey);
            if (input.equals("DribNit") || input.equals("DribNlt"))
            {
                isTerminal = input.equals("DribNit");
                Utils.write(out, subscriberKey, subscriberKey);

                input = Utils.read(in, subscriberKey);
                if (input.equals(publisherKey))
                {
                    // send my flavor
                    Utils.write(out, subscriberKey, subscriberRepo.getLibraryData().libraries.flavor);

                    logger.info("Authenticated " + (isTerminal ? "terminal" : "automated") + " session: " + publisherRepo.getLibraryData().libraries.description);
                    valid = true;
                }
            }
        }
        catch (Exception e)
        {
            logger.error(e.getMessage());
        }
        return valid;
    } // handshake

    /**
     * Process a connection request to the Daemon service.
     * <p>
     * The Daemon service provides an interface for this instance.
     */
    @SuppressWarnings("Duplicates")
    public void process(Socket aSocket) throws IOException
    {
        socket = aSocket;
        port = aSocket.getPort();
        address = aSocket.getInetAddress();
        int attempts = 0;
        String line;
        String basePrompt = ": ";
        String prompt = basePrompt;
        boolean tout = false;

        // setup i/o
        aSocket.setSoTimeout(120000); // time-out so this thread does not hang server

        in = new DataInputStream(aSocket.getInputStream());
        out = new DataOutputStream(aSocket.getOutputStream());

        connected = true;

        if (!handshake())
        {
            stop = true; // just hang-up on the connection
            logger.info("Connection to " + publisherRepo.getLibraryData().libraries.site + " failed handshake");
        }

        if (isTerminal)
        {
            response = "Enter 'help' for information\r\n"; // "Enter " checked in ClientStty.checkBannerCommands()
        }
        else // is automation
        {
            response = "CMD";

            //  -S Subscriber collection file
            if (cfg.isForceCollection())
            {
                response = response + ":RequestCollection";
            }

            //  -t Subscriber targets
            if (cfg.isForceTargets())
            {
                response = response + ":RequestTargets";
            }
        }

        // prompt for & process interactive commands
        while (stop == false)
        {
            try
            {
                // prompt the user for a command
                if (!tout)
                {
                    Utils.write(out, subscriberKey, response + (isTerminal ? prompt : ""));
                }
                tout = false;
                response = "";

                line = Utils.read(in, subscriberKey);
                if (line == null)
                {
                    logger.info("EOF line");
                    stop = true;
                    break; // exit on EOF
                }

                if (line.trim().length() < 1)
                {
                    response = "\r";
                    continue;
                }

                logger.info("Processing command: " + line);

                // parse the command
                StringTokenizer t = new StringTokenizer(line);
                if (!t.hasMoreTokens())
                    continue; // ignore if empty

                String theCommand = t.nextToken();

                // -------------- authorized level password -----------------
                if (theCommand.equalsIgnoreCase("auth"))
                {
                    ++attempts;
                    String pw = "";
                    if (t.hasMoreTokens())
                        pw = t.nextToken(); // get the password
                    if (cfg.getAuthorizedPassword().equals(pw.trim()))
                    {
                        response = "password accepted\r\n";
                        authorized = true;
                        prompt = "$ ";
                        logger.info("Command auth accepted");
                    }
                    else
                    {
                        logger.warn("Auth password attempt failed using: " + pw);
                        if (attempts >= 3) // disconnect on too many attempts
                        {
                            logger.error("Too many failures, disconnecting");
                            break;
                        }
                    }
                    continue;
                }

                // -------------- return collection file --------------------
                if (theCommand.equalsIgnoreCase("collection"))
                {
                    try
                    {
                        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
                        LocalDateTime now = LocalDateTime.now();
                        String stamp = dtf.format(now);

                        String location = subscriberRepo.getJsonFilename() + "_collection-generated-" + stamp + ".json";
                        cfg.setExportCollectionFilename(location);

                        // if -s then scan
                        if (cfg.getSubscriberLibrariesFileName().length() > 0)
                        {
                            for (Library subLib : subscriberRepo.getLibraryData().libraries.bibliography)
                            {
                                if (subLib.items != null)
                                {
                                    subLib.items = null; // clear any existing data
                                }
                                subscriberRepo.scan(subLib.name);
                            }
                        }
                        // otherwise it must be -S so do not scan
                        subscriberRepo.exportCollection();

                        response = new String(Files.readAllBytes(Paths.get(location)));
                    }
                    catch (MungerException e)
                    {
                        logger.error(e.getMessage());
                    }
                    continue;
                }

                // -------------- logout ------------------------------------
                if (theCommand.equalsIgnoreCase("logout"))
                {
                    if (authorized)
                    {
                        authorized = false;
                        prompt = basePrompt;
                        continue;
                    }
                    else
                    {
                        theCommand = "quit";
                        // let the logic fall through to the 'quit' handler below
                    }
                }

                // -------------- quit, bye, exit ---------------------------
                if (theCommand.equalsIgnoreCase("quit") || theCommand.equalsIgnoreCase("bye") || theCommand.equalsIgnoreCase("exit"))
                {
                    Utils.write(out, subscriberKey, "End-Execution");
                    stop = true;
                    break; // break the loop
                }

                // -------------- available disk space ----------------------
                if (theCommand.equalsIgnoreCase("space"))
                {
                    String location = "";
                    if (t.hasMoreTokens())
                    {
                        location = t.nextToken();
                        long space = Utils.availableSpace(location);
                        if (isTerminal)
                        {
                            response = Utils.formatLong(space);
                        }
                        else
                        {
                            response = String.valueOf(space);
                        }
                    }
                    else
                    {
                        response = (isTerminal ? "space command requires a location\r\n" : "0");
                    }
                    continue;
                }

                // -------------- status information ------------------------
                if (theCommand.equalsIgnoreCase("status"))
                {
                    if (!authorized)
                    {
                        response = "not authorized\r\n";
                    }
                    else
                    {
                        response = ServeStty.getInstance().dumpStatistics();
                        response += dumpStatistics();
                    }
                    continue;
                }

                // -------------- return targets file -----------------------
                if (theCommand.equalsIgnoreCase("targets"))
                {
                    try
                    {
                        response = new String(Files.readAllBytes(Paths.get(cfg.getTargetsFilename())));
                    }
                    catch (Exception e)
                    {
                        logger.error(e.getMessage());
                    }
                    continue;
                }

                // -------------- help! -------------------------------------
                if (theCommand.equalsIgnoreCase("help") || theCommand.equals("?"))
                {
                    // @formatter:off
                    response = "\r\nAvailable commands, not case sensitive:\r\n";

                    if (authorized)
                    {
                        response += "  status = server and console status information\r\n" +
                                "\r\n" + "" +
                                " And:\r\n";
                    }

                    response += "  auth [password] = access Authorized commands\r\n" +
                            "  collection = get collection data from remote\r\n" +
                            "  space [location] = free space at location on remote\r\n" +
                            "  targets = get targets file from remote\r\n" +
                            "\r\n  help or ? = this list\r\n" +
                            "  logout = exit current level\r\n" +
                            "  quit, bye, exit = disconnect\r\n" +
                            "\r\n";
                    // @formatter:on
                    continue;
                }

                response = "\r\nunknown command '" + theCommand + "', use 'help' for information\r\n";

            } // try
            catch (Exception e)
            {
                Utils.write(out, subscriberKey, e.getMessage());
                connected = false;
                break;
            }
        } // while

        if (stop)
        {
            // all done, close everything
            if (logger != null)
            {
                logger.info("Close connection on port " + port + " to " + address.getHostAddress());
            }
            out.close();
            in.close();

//            logger.info("stopping services");
            Runtime.getRuntime().exit(0);
        }

    } // process

    /**
     * Request the Daemon service to stop
     */
    public void requestStop()
    {
        this.stop = true;
        logger.info("Requesting stop for session on port " + socket.getPort() + " to " + socket.getInetAddress());
    } // requestStop

} // Daemon
