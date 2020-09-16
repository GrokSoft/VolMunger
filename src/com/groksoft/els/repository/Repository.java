package com.groksoft.els.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.groksoft.els.Configuration;
import com.groksoft.els.MungerException;
import com.groksoft.els.Utils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The type Repository.
 */
public class Repository
{
    public static final boolean NO_VALIDATE = false;
    public static final boolean PUBLISHER = true;
    public static final boolean SUBSCRIBER = false;
    public static final boolean VALIDATE = true;
    private transient Configuration cfg = null;
    private String jsonFilename = "";
    private LibraryData libraryData = null;
    private transient Logger logger = LogManager.getLogger("applog");

    /**
     * Instantiates a new Collection.
     *
     * @param config Configuration
     */
    public Repository(Configuration config)
    {
        cfg = config;
    }

    /**
     * Dump collection.
     */
    public void dump()
    {
        System.out.println("  Libraries from " + getJsonFilename());
        System.out.println("    Description: " + libraryData.libraries.description);
        System.out.println("           Host: " + libraryData.libraries.host);
        System.out.println("         Listen: " + libraryData.libraries.listen);
        System.out.println("            Key: " + libraryData.libraries.key);
        System.out.println("    Case-sensitive: " + libraryData.libraries.case_sensitive);
        System.out.println("    Ignore patterns:");
        for (String patt : libraryData.libraries.ignore_patterns)
        {
            System.out.println("      " + patt);
        }
        System.out.println("    Bibliography:");
        for (Library lib : libraryData.libraries.bibliography)
        {
            System.out.println("      Name: " + lib.name);
            System.out.println("      Sources:");
            for (String src : lib.sources)
            {
                System.out.println("        " + src);
            }
        }
    }

    /**
     * Export libraries to JSON.
     *
     * @throws MungerException the els exception
     */
    public void exportCollection() throws MungerException
    {
        String json;
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        logger.info("Writing collection file " + cfg.getExportCollectionFilename());
        json = gson.toJson(libraryData);
        try
        {
            PrintWriter outputStream = new PrintWriter(cfg.getExportCollectionFilename());
            outputStream.println(json);
            outputStream.close();
        }
        catch (FileNotFoundException fnf)
        {
            throw new MungerException("Exception while writing collection file " + cfg.getExportCollectionFilename() + " trace: " + Utils.getStackTrace(fnf));
        }
    }

    /**
     * Export libraries to text.
     *
     * @throws MungerException the els exception
     */
    public void exportText() throws MungerException
    {
        logger.info("Writing text file " + cfg.getExportTextFilename());

        try
        {
            PrintWriter outputStream = new PrintWriter(cfg.getExportTextFilename());
            for (Library lib : libraryData.libraries.bibliography)
            {
                for (Item item : lib.items)
                {
                    if (!item.isDirectory())
                    {
                        if (!ignore(item))
                        {
                            outputStream.println(item.getItemPath());
                        }
                    }
                }
            }

            outputStream.close();
        }
        catch (FileNotFoundException fnf)
        {
            throw new MungerException("Exception while writing text file " + cfg.getExportTextFilename() + " trace: " + Utils.getStackTrace(fnf));
        }
    }

    /**
     * Gets LibraryData filename.
     *
     * @return the LibraryData filename
     */
    public String getJsonFilename()
    {
        return jsonFilename;
    }

    /**
     * Sets LibraryData file.
     *
     * @param jsonFilename of the LibraryData file
     */
    public void setJsonFilename(String jsonFilename)
    {
        this.jsonFilename = jsonFilename;
    }

    /**
     * Get specific library
     * <p>
     * Do these Libraries have a particular Library?
     *
     * @param libraryName the library name
     * @return the Library
     */
    public Library getLibrary(String libraryName) throws MungerException
    {
        boolean has = false;
        Library retLib = null;
        for (Library lib : libraryData.libraries.bibliography)
        {
            if (lib.name.equalsIgnoreCase(libraryName))
            {
                if (has)
                {
                    throw new MungerException("Library " + lib.name + " found more than once in " + getJsonFilename());
                }
                has = true;
                retLib = lib;
            }
        }
        return retLib;
    }

    /**
     * Gets LibraryData.
     *
     * @return the library
     */
    public LibraryData getLibraryData()
    {
        return libraryData;
    }

    /**
     * Get file separator
     *
     * @return File separator string single character
     * @throws MungerException
     */
    public String getSeparator() throws MungerException
    {
        String sep = getWriteSeparator();
        if (sep.equalsIgnoreCase("\\\\"))
            sep = "\\";
        return sep;
    }

    /**
     * Get file separator for writing
     * @return file separator string, may be multiple characters, e.g. \\
     * @throws MungerException
     */
    public String getWriteSeparator() throws MungerException
    {
        return Utils.getFileSeparator(libraryData.libraries.flavor);
    }

    public String getItemName(Item item) throws MungerException
    {
        String path = item.getItemPath();
        String sep = getSeparator();
        String name = path.substring(path.lastIndexOf(sep) + 1, path.length());
        return name;
    }

    /**
     * Has directory true/false.
     * <p>
     * String itemPath is expected to have been converted to pipe character file separators using Utils.pipe().
     *
     * @param libraryName the library name
     * @param itemPath    the match
     * @return the string, null if not found
     */
    public String hasDirectory(String libraryName, String itemPath) throws MungerException
    {
        String match = itemPath;
        Item foundItem = null;
        int i = match.lastIndexOf("|");
        if (i < 0)
        {
            return null;
        }
        String path = match.substring(0, i);
        if (path.length() < 1)
        {
            path = match.substring(0, match.lastIndexOf("|"));
        }
        match = path;
        path = null;
        for (Library lib : libraryData.libraries.bibliography)
        {
            if (lib.name.equalsIgnoreCase(libraryName))
            {
                foundItem = null;
                if (lib.items != null)
                {
                    for (Item item : lib.items)
                    {
                        if (libraryData.libraries.case_sensitive)
                        {
                            if (Utils.pipe(this, item.getItemPath()).equals(match))
                            {
                                foundItem = item;
                                break;
                            }
                        }
                        else
                        {
                            if (Utils.pipe(this, item.getItemPath()).equalsIgnoreCase(match))
                            {
                                foundItem = item;
                                break;
                            }
                        }
                    }
                }
                else
                {
                    logger.warn("Subscriber library '" + lib.name + "' has no items. Is command-line configured correctly?");
                }
                if (foundItem != null && foundItem.isDirectory())
                {
                    path = foundItem.getFullPath().substring(0, foundItem.getFullPath().lastIndexOf(foundItem.getItemPath()) - 1);
                }
                break;
            }
        }
        return path;
    }

    /**
     * Has specific item true/false.
     * <p>
     * Does this Library have a particular item?
     *
     * @param libraryName the library name
     * @param match       the match
     * @return the boolean
     */
    public Item hasItem(String libraryName, String match) throws MungerException
    {
        Item has = null;
        for (Library lib : libraryData.libraries.bibliography)
        {
            if (lib.name.equalsIgnoreCase(libraryName))
            {
                for (Item item : lib.items)
                {
                    if (libraryData.libraries.case_sensitive)
                    {
                        if (Utils.pipe(this, item.getItemPath()).equals(match))
                        {
                            has = item;
                            break;
                        }
                    }
                    else
                    {
                        if (Utils.pipe(this, item.getItemPath()).equalsIgnoreCase(match))
                        {
                            has = item;
                            break;
                        }
                    }
                }
                if (has != null)
                {
                    break;  // break outer loop also
                }
            }
        }
        return has;
    }

    /**
     * Determine if item should be ignored
     *
     * @param item
     * @return
     */
    public boolean ignore(Item item) throws MungerException
    {
        String str = "";
        String str1 = "";
        boolean ret = false;
        String name = getItemName(item);

        for (Pattern patt : getLibraryData().libraries.compiledPatterns)
        {
            str = patt.toString();
            str1 = str.replace("?", ".?").replace("*", ".*?");
            if (name.matches(str1))
            {
                ret = true;
                break;
            }
        }
        return ret;
    }

    /**
     * Is Initialized indicator
     *
     * @returns boolean true/false
     */
    public boolean isInitialized()
    {
        if (this.libraryData != null && (this.jsonFilename != null && this.jsonFilename.length() > 0))
            return true;
        else
            return false;
    }

    /**
     * Normalize all JSON paths based on "flavor"
     *
     */
    public void normalize()
    {
        if (libraryData != null)
        {
            // if listen is empty use host
            if (libraryData.libraries.listen == null ||
                libraryData.libraries.listen.length() < 1)
            {
                libraryData.libraries.listen = libraryData.libraries.host;
            }

            String flavor = libraryData.libraries.flavor.toLowerCase();
            String from = "";
            String to = "";
            switch (flavor)
            {
                case Libraries.LINUX:
                    from = "\\\\";
                    to = "/";
                    break;

                case Libraries.WINDOWS:
                    from = "/";
                    to = "\\\\";
                    break;
            }

            for (Library lib : libraryData.libraries.bibliography)
            {
                if (lib.sources != null)
                {
                    for (int i = 0; i < lib.sources.length; ++i)
                    {
                        lib.sources[i] = normalizeSubst(lib.sources[i], from, to);
                    }
                }
                if (lib.items != null)
                {
                    for (Item item : lib.items)
                    {
                        item.setItemPath(normalizeSubst(item.getItemPath(), from, to));
                        item.setFullPath(normalizeSubst(item.getFullPath(), from, to));
                    }
                }
            }
        }
    }

    /**
     * Normalize a path
     * <p>
     * @param toFlavor Desired flavor of separators
     * @param path Path to normalize
     * @return path Normalized path for desired flavor
     * @throws MungerException
     */
    public String normalize(String toFlavor, String path) throws MungerException
    {
        if (!toFlavor.equalsIgnoreCase(libraryData.libraries.flavor))
        {
            String to = Utils.getFileSeparator(toFlavor);
            path = normalizeSubst(path, Utils.getFileSeparator(libraryData.libraries.flavor), to);
        }
        return path;
    }

    private String normalizeSubst(String path, String from, String to)
    {
        return path.replaceAll(from, to);
    }

    /**
     * Read library.
     *
     * @param filename The JSON Libraries filename
     * @throws MungerException the els exception
     */
    public void read(String filename) throws MungerException
    {
        try
        {
            String json;
            if (libraryData != null)
                libraryData = null;
            Gson gson = new Gson();
            logger.info("Reading Libraries file " + filename);
            setJsonFilename(filename);
            json = new String(Files.readAllBytes(Paths.get(filename)));
            libraryData = gson.fromJson(json, LibraryData.class);
            normalize();
        }
        catch (IOException ioe)
        {
            throw new MungerException("Exception while reading libraries " + filename + " trace: " + Utils.getStackTrace(ioe));
        }
    }

    /**
     * Scan a specific library name.
     *
     * @throws MungerException the els exception
     */
    public void scan(String libraryName) throws MungerException
    {
        for (Library lib : libraryData.libraries.bibliography)
        {
            if (libraryName.length() > 0)
            {
                if (!libraryName.equalsIgnoreCase(lib.name))
                    continue;
            }
            logger.info("Scanning " + getLibraryData().libraries.description + ": " + lib.name);
            for (String src : lib.sources)
            {
                logger.info("  " + src);
                scanDirectory(lib, src, src);
            }
            sort(lib);
        }
        normalize();
    }

    /**
     * Scan a specific directory, recursively.
     *
     * @param directory the directory
     * @throws MungerException the els exception
     */
    private void scanDirectory(Library library, String base, String directory) throws MungerException
    {
        Item item = null;
        String fullPath = "";
        String itemPath = "";
        long size = 0;
        boolean isDir = false;
        boolean isSym = false;
        Path path = Paths.get(directory);

        if (library.items == null)
        {
            library.items = new ArrayList<>();
        }

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(path))
        {
            for (Path entry : directoryStream)
            {
                item = new Item();
                fullPath = entry.toString();                            // full path
                item.setFullPath(fullPath);
                path = Paths.get(fullPath);
                isDir = Files.isDirectory(path);                        // is directory check
                item.setDirectory(isDir);
                size = (isDir ? 0L : Files.size(path));                 // size
                item.setSize(size);
                itemPath = fullPath.substring(base.length() + 1);       // item path
                item.setItemPath(itemPath);
                isSym = Files.isSymbolicLink(path);                     // is symbolic link check
                item.setSymLink(isSym);
                item.setLibrary(library.name);                          // the library name
                library.items.add(item);
                //logger.debug(entry.toString());
                if (isDir)
                {
                    scanDirectory(library, base, item.getFullPath());
                }
            }
        }
        catch (IOException ioe)
        {
            throw new MungerException("Exception reading directory " + directory + " trace: " + Utils.getStackTrace(ioe));
        }
    }

    /**
     * Sort collection.
     */
    public void sort(Library lib)
    {
        lib.items.sort((item1, item2) -> item1.getItemPath().compareToIgnoreCase(item2.getItemPath()));
    }

    /**
     * Validate LibraryData.
     *
     * @throws MungerException the els exception
     */
    public void validate() throws MungerException
    {
        long minimumSize;

        if (libraryData == null)
        {
            throw new MungerException("Libraries are null");
        }

        Libraries lbs = libraryData.libraries;
        if (lbs == null)
        {
            throw new MungerException("libraries must be defined");
        }

        if (lbs.description == null || lbs.description.length() == 0)
        {
            throw new MungerException("libraries.description must be defined");
        }
        if (lbs.case_sensitive == null)
        {
            throw new MungerException("libraries.case_sensitive true/false must be defined");
        }

        if (lbs.ignore_patterns.length > 0)
        {
            Pattern patt = null;
            try
            {
                for (String s : lbs.ignore_patterns)
                {
                    patt = Pattern.compile(s);
                    lbs.compiledPatterns.add(patt);
                }
            }
            catch (PatternSyntaxException pe)
            {
                throw new MungerException("Pattern " + patt + " has bad regular expression (regex) syntax");
            }
            catch (IllegalArgumentException iae)
            {
                throw new MungerException("Pattern " + patt + " has bad flags");
            }
        }

        if (lbs.bibliography == null)
        {
            throw new MungerException("libraries.bibliography must be defined");
        }

        logger.info("Validating Libraries " + getJsonFilename());
        for (int i = 0; i < lbs.bibliography.length; i++)
        {
            Library lib = lbs.bibliography[i];
            if (lib.name == null || lib.name.length() == 0)
            {
                throw new MungerException("bibliography.name " + i + " must be defined");
            }
            if (lib.items == null || lib.items.size() == 0)
            {
                if (lib.sources == null || lib.sources.length == 0)
                {
                    throw new MungerException("bibliography.sources " + i + " must be defined");
                }
                else
                {
                    // Verify paths
                    for (int j = 0; j < lib.sources.length; j++)
                    {
                        if (lib.sources[j].length() == 0)
                        {
                            throw new MungerException("bibliography[" + i + "].sources[" + j + "] must be defined");
                        }
                        if (Files.notExists(Paths.get(lib.sources[j])))
                        {
                            throw new MungerException("bibliography[" + i + "].sources[" + j + "]: " + lib.sources[j] + " does not exist");
                        }
                        logger.debug("  src: " + lib.sources[j]);
                    }
                }
            }
        }
    }

}