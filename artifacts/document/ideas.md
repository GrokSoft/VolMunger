
## Media
 * Mark all not-so-good movies with fubar, or whatever
 * Go over how to handle Director cuts See Underworld
 * Search function
 * REMEMBER - Put NEW Targets in library so they are scanned

## Code
 * Auto Renamer - xpaus
 * For TV Shows do searches ignoring season folders
 * For .els actions:
   - transcode option for high resource demand items, 
     e.g. Bring It On, Sons of Anarchy
 * Add a time metric.

# Ideas

The existence of an empty (or not) els.json file triggers the
"I Win" behavior.

"I Win" means - make the target look like my source - including any
deletions necessary.

If els.json files exist on both systems it is a conflict.
The conflict is flagged, logged into a input file for re-rerun purposes,
then skipped for that munge run.

Because volumes can be quite large there should be a mechanism
to feed the conflicts back into another ELS run so once
the conflict is resolved it is easier to complete the synch.


## Organization
 * A publisher is the provider of the data.
 * A subscriber is the consumer of the data.
 * Both have Libraries.
 * A subscriber libraries file may be a subset, i.e. only what
   content is desired from the publisher.

 * Libraries describe one or more Library objects.
 * Each Library is described by:
    - a name 
    - one or more sources (directories)
 
 * A Collection is the list of Items from scanning one or more libraries.
 * The Collection file is described by:
   - a list of the libraries 
   - a list of each item in each library
 * An item can be either a directory or file.

 * A Repository is a generic term that is either a Library or Collection.

 * A Group is an internal set of items in the same
   directory. Items are synchronized by group.
   - This works for a movie and that directory's contents,
   - and works for a TV show and a season directory.

### Collection Files
 * No two library names may be the same, i.e. library names must be unique

### Target Files
 * One or more may be specified
 * Space check is performed before copying
 * Automatically rolls-over to the next target
   
---

## els.json control file Use Cases

 1. els.json, Deletes, very rare. 

 2. els.json, Changes of existing file(s)
   - Different image, subtitles ... any file
   - Cannot trust dates

 3. els.json, Moves do not matter, this is location inspecific with a library

 4. els.json, Move to library, e.g. Movies to Documentary Movies
 
