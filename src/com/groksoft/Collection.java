package com.groksoft;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

// see https://github.com/google/gson
import com.google.gson.Gson;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The type Collection.
 */
public class Collection
{
    private Logger logger = LogManager.getLogger("applog");
    private Configuration cfg = null;

    // data members
    private Control control = null;
    private String collectionFile = "";
    private List<Item> items = new ArrayList<>();

// Methods:
    // A load method to read a collection.control file
    // A validate method to check the syntax and existence of the elements in a collection.control file
    // A scanAll method to scanAll and generate the set of Item objects
    // A sort method, by context
    // A duplicates method to check for duplicate contexts in the Collection - possibly enforced by the selected Java collection requiring a unique key

    /**
     * Instantiates a new Collection.
     */
    public Collection() {
        cfg = Configuration.getInstance();
    }

    /**
     * Read control.
     *
     * @param filename the filename
     * @throws MongerException the monger exception
     */
    public void readControl(String filename) throws MongerException {
        try {
            String json;
            Gson gson = new Gson();
            logger.info("Reading collection file " + filename);
            setCollectionFile(filename);
            json = new String(Files.readAllBytes(Paths.get(filename)));
            json = json.replaceAll("[\n\r]", "");
            control = gson.fromJson(json, Control.class);
        } catch (IOException ioe) {
            throw new MongerException("Exception while reading " + filename + " trace: " + Utils.getStackTrace(ioe));
        }
    }

    /**
     * Validate control.
     *
     * @throws MongerException the monger exception
     */
    public void validateControl() throws MongerException {
        long minimumSize;

        if (getControl() == null) {
            throw new MongerException("Control is null");
        }

        if (control.metadata.name == null || control.metadata.name.length() == 0) {
            throw new MongerException("metadata.name must be defined");
        }
        if (control.metadata.case_sensitive == null) {
            throw new MongerException("metadata.case_sensitive true/false must be defined");
        }

        for (int i = 0; i < control.libraries.length; i++) {
            if (control.libraries[i].definition == null) {
                throw new MongerException("libraries.definition[" + i + "] must be defined");
            }
            if (control.libraries[i].definition.name == null || control.libraries[i].definition.name.length() == 0) {
                throw new MongerException("library[" + i + "].name must be defined");
            }
            // minimum is optional
            if (control.libraries[i].definition.minimum != null && control.libraries[i].definition.minimum.length() > 0) {
                minimumSize = Utils.getScaledValue(control.libraries[i].definition.minimum);
                if (minimumSize == -1) {
                    throw new MongerException("control.libraries[" + i + "].definition.minimum is invalid");
                }
            }
            // genre is optional

            if (control.libraries[i].sources == null || control.libraries[i].sources.length == 0) {
                throw new MongerException("libraries[" + i + "].sources must be defined");
            } else {
                // Verify paths
                for (int j = 0; j < control.libraries[i].sources.length; j++) {
                    if (control.libraries[i].sources[j].length() == 0) {
                        throw new MongerException("libraries[" + i + "].sources[" + j + "] must be defined");
                    }
                    if (Files.notExists(Paths.get(control.libraries[i].sources[j]))) {
                        throw new MongerException("control.libraries[" + i + "].sources[" + j + "]: " + control.libraries[i].sources[j] + " does not exist");
                    }
                    logger.debug("DIR: " + control.libraries[i].sources[j]);
                }
            }
            if (control.libraries[i].targets == null || control.libraries[i].targets.length == 0) {
                throw new MongerException("libraries.sources[" + i + "] must be defined");
            } else {
                // Verify paths
                for (int j = 0; j < control.libraries[i].targets.length; j++) {
                    if (control.libraries[i].targets[j].length() == 0) {
                        throw new MongerException("libraries[" + i + "].targets[" + j + "] must be defined");
                    }
                    if (Files.notExists(Paths.get(control.libraries[i].targets[j]))) {
                        throw new MongerException("control.libraries[" + i + "].targets[" + j + "]: " + control.libraries[i].targets[j] + " does not exist");
                    }
                    logger.debug("DIR: " + control.libraries[i].targets[j]);
                }
            }
        }
        logger.info("Validation successful");
    }

    /**
     * Scan All libraries.
     *
     * @throws MongerException the monger exception
     */
    public void scanAll() throws MongerException {
        for (int i = 0; i < control.libraries.length; i++) {

            // todo decide if a single library was specified

            for (int j = 0; j < control.libraries[i].sources.length; j++) {
                scanDirectory(control.libraries[i].definition.name, control.libraries[i].sources[j], control.libraries[i].sources[j]);
            }
        }

        System.out.println("PRESORTED:");
        dumpCollection();

        sortCollection();

        System.out.println("\r\nSORTED:");
        dumpCollection();

        if (cfg.getExportFilename().length() < 1) {
            // todo write out to file
            // Idea: Export to a JSON file; then a load of that file creates an ArrayList of Items
        }
    }

    /**
     * Scan a specific directory, recursively.
     *
     * @param directory the directory
     * @throws MongerException the monger exception
     */
    private void scanDirectory(String library, String base, String directory) throws MongerException {
        Item item = null;
        String fullPath = "";
        String itemPath = "";
        boolean isDir = false;
        boolean isSym = false;
        Path path = Paths.get(directory);

        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(path)) {
            for (Path entry : directoryStream) {
                item = new Item();
                fullPath = entry.toString();                            // full path
                item.setFullPath(fullPath);
                path = Paths.get(fullPath);
                isDir = Files.isDirectory(path);                        // is directory check
                item.setDirectory(isDir);
                itemPath = fullPath.substring(base.length() + 1);       // item path
                item.setItemPath(itemPath);
                isSym = Files.isSymbolicLink(path);                     // is symbolic link check
                item.setSymLink(isSym);
                item.setLibrary(library);                               // the library name
                this.items.add(item);
                //logger.debug(entry.toString());
                if (isDir) {
                    scanDirectory(library, base, item.getFullPath());
                }
            }
        } catch (IOException ioe) {
            throw new MongerException("Exception reading directory " + directory + " trace: " + Utils.getStackTrace(ioe));
        }
    }

    /**
     * Sort collection.
     */
    public void sortCollection() {
        Collections.sort(items, new Comparator<Item>()
        {
            @Override
            public int compare(Item item1, Item item2) {
                return item1.getItemPath().compareTo(item2.getItemPath());
            }
        });
    }

    /**
     * Dump collection.
     */
    public void dumpCollection() {
        Iterator<Item> itemIterator = items.iterator();
        while (itemIterator.hasNext()) {
            Item item = itemIterator.next();
            System.out.println("    " + item.getItemPath());
        }
    }

    /**
     * Export collection.
     */
    public void exportCollection() throws MongerException {
        String json;
        Gson gson = new Gson();
        logger.info("Writing item file " + cfg.getExportFilename());
        ItemExport export = new ItemExport();
        export.control = control;
        export.items = items;
        json = gson.toJson(export);
        try {
            PrintWriter outputStream = new PrintWriter(cfg.getExportFilename());
            outputStream.println(json);
            outputStream.close();
        } catch (FileNotFoundException fnf) {
            throw new MongerException("Exception while writing item file " + cfg.getExportFilename() + " trace: " + Utils.getStackTrace(fnf));
        }
    }

    public void importItems() throws MongerException {
        String filename = cfg.getImportFilename();
        try {
            String json;
            Gson gson = new Gson();
            logger.info("Reading item file " + filename);
            json = new String(Files.readAllBytes(Paths.get(filename)));
            json = json.replaceAll("[\n\r]", "");
            ItemExport itemExport = gson.fromJson(json, ItemExport.class);
            setCollectionFile(filename);
            control = itemExport.control;
            items = itemExport.items;
        } catch (IOException ioe) {
            throw new MongerException("Exception while reading " + filename + " trace: " + Utils.getStackTrace(ioe));
        }
    }

    /**
     * Has boolean.
     * <p>
     * Does this Collection have an item with an itemPath the same as the passed itemPath?
     *
     * @param path the path
     * @return the boolean
     */
    public boolean has(String path) {
        boolean has = false;
        Iterator<Item> iterator = getItems().iterator();
        while (iterator.hasNext()) {
            Item item = iterator.next();
            if (getControl().metadata.case_sensitive) {
                if (path.equalsIgnoreCase(item.getItemPath())) {
                    has = true;
                    break;
                }
            } else {
                if (path.equals(item.getItemPath())) {
                    has = true;
                    break;
                }
            }
        }
        return has;
    }

    /**
     * Gets collection file.
     *
     * @return the collection file
     */
    public String getCollectionFile() {
        return collectionFile;
    }

    /**
     * Sets collection file.
     *
     * @param collectionFile the collection file
     */
    public void setCollectionFile(String collectionFile) {
        this.collectionFile = collectionFile;
    }

    /**
     * Gets control.
     *
     * @return the control
     */
    public Control getControl() {
        return control;
    }

    /**
     * Gets items.
     *
     * @return the items
     */
    public List<Item> getItems() {
        return items;
    }

    /**
     * Sets items.
     *
     * @param items the items
     */
    public void setItems(List<Item> items) {
        this.items = items;
    }

    //==================================================================================================================
    // Inner classes for Gson collection (control) file

    /**
     * The type Control
     * <p>
     * Top-level object for a publisher or subscriber collection file.
     */
    public class Control
    {
        /**
         * The Metadata.
         */
        public Metadata metadata;

        /**
         * The Libraries.
         */
        public Libraries[] libraries;
    }

    /**
     * The type Metadata.
     */
    public class Metadata
    {
        /**
         * The Name.
         */
        public String name;

        /**
         * The Case sensitive.
         */
        public Boolean case_sensitive;
    }

    /**
     * The type Libraries.
     */
    public class Libraries
    {
        /**
         * The Definition.
         */
        public Definition definition;

        /**
         * The Sources.
         */
        public String[] sources;

        /**
         * The Targets.
         */
        public String[] targets;

    }

    /**
     * The type Definition.
     */
    public class Definition
    {
        /**
         * The Name.
         */
        public String name;

        /**
         * The Minimum.
         */
        public String minimum;

        /**
         * If it has genres.
         */
        public boolean genre;
    }

    //==================================================================================================================
    // Inner classes for Gson item export file

    /**
     * The type Item export.
     */
    public class ItemExport
    {
        /**
         * The type Control
         * <p>
         * Top-level object for a publisher or subscriber collection file.
         */
        public Control control;

        /**
         * The Items.
         */
        public List<Item> items;
    }

}
