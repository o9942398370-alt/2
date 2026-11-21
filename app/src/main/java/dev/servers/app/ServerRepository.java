package dev.servers.app;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Owns the app-private Bedrock server/build/pack library. */
public final class ServerRepository {
    private static final long MAX_EXPANDED_BYTES = 1_500L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRIES = 30_000;

    private final Context context;
    private final File serversDir;
    private final File buildsDir;
    private final File packsDir;

    public ServerRepository(Context context) {
        this.context = context.getApplicationContext();
        this.serversDir = new File(context.getFilesDir(), "servers");
        this.buildsDir = new File(context.getFilesDir(), "builds");
        this.packsDir = new File(context.getFilesDir(), "packs");
        try {
            ensureDirectory(serversDir);
            ensureDirectory(buildsDir);
            ensureDirectory(packsDir);
        } catch (IOException error) {
            throw new IllegalStateException("Could not initialize server storage", error);
        }
    }

    public File getServersDirectory() { return serversDir; }
    public File getBuildsDirectory() { return buildsDir; }
    public File getPacksDirectory() { return packsDir; }

    public List<BedrockServer> scanServers() {
        File[] directories = serversDir.listFiles(File::isDirectory);
        if (directories == null) return Collections.emptyList();
        List<BedrockServer> found = new ArrayList<>();
        for (File directory : directories) {
            BedrockServer server = new BedrockServer(directory);
            if (server.isValidBedrockServer()) found.add(server);
        }
        found.sort(Comparator.comparing(s -> s.name.toLowerCase(Locale.ROOT)));
        return found;
    }

    public BedrockServer importServerFolder(Uri folderUri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        DocumentFile source = DocumentFile.fromTreeUri(context, folderUri);
        if (source == null || !source.isDirectory()) throw new IOException("The selected folder cannot be opened");
        String name = safeName(source.getName(), "BedrockServer");
        File target = uniqueDirectory(serversDir, name);
        ensureDirectory(target);
        try {
            copyDocumentDirectory(source, target, resolver);
        } catch (IOException error) {
            deleteRecursively(target);
            throw error;
        }
        File candidate = findServerRoot(target, 5);
        if (candidate == null) {
            deleteRecursively(target);
            throw new IOException("This folder does not contain a Bedrock Linux server (bedrock_server and server.properties)");
        }
        if (!candidate.equals(target)) {
            File normalized = uniqueDirectory(serversDir, safeName(candidate.getName(), "BedrockServer"));
            if (!candidate.renameTo(normalized)) throw new IOException("Could not organize the imported Bedrock server");
            deleteRecursively(target);
            candidate = normalized;
        }
        // Preserve the executable bit for a future bundled Linux runtime.
        //noinspection ResultOfMethodCallIgnored
        new File(candidate, "bedrock_server").setExecutable(true, false);
        return new BedrockServer(candidate);
    }

    public BedrockServer importBuildZip(Uri archiveUri, String displayName) throws IOException {
        File staging = uniqueDirectory(buildsDir, safeName(displayName, "BedrockBuild"));
        ensureDirectory(staging);
        try (InputStream input = context.getContentResolver().openInputStream(archiveUri)) {
            if (input == null) throw new IOException("Could not open the selected archive");
            unzipSafely(input, staging);
        } catch (Exception error) {
            deleteRecursively(staging);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not extract the Bedrock build", error);
        }

        File candidate = findServerRoot(staging, 7);
        if (candidate == null) {
            deleteRecursively(staging);
            throw new IOException("The archive is not a Bedrock Linux server build. A bedrock_server executable and server.properties are required.");
        }
        File destination = uniqueDirectory(serversDir, safeName(candidate.getName(), "BedrockServer"));
        if (candidate.equals(staging)) {
            if (!staging.renameTo(destination)) throw new IOException("Could not move the imported build into the server library");
        } else {
            if (!candidate.renameTo(destination)) throw new IOException("Could not move the imported Bedrock server folder");
            deleteRecursively(staging);
        }
        // ZIP extraction does not preserve POSIX mode bits; restore the BDS executable flag.
        //noinspection ResultOfMethodCallIgnored
        new File(destination, "bedrock_server").setExecutable(true, false);
        return new BedrockServer(destination);
    }

    public File importPack(Uri archiveUri, String displayName) throws IOException {
        File destination = uniqueDirectory(packsDir, safeName(displayName, "BedrockPack"));
        ensureDirectory(destination);
        try (InputStream input = context.getContentResolver().openInputStream(archiveUri)) {
            if (input == null) throw new IOException("Could not open the selected pack");
            unzipSafely(input, destination);
        } catch (Exception error) {
            deleteRecursively(destination);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not extract the pack", error);
        }
        File manifest = findFile(destination, "manifest.json", 5);
        if (manifest == null) {
            deleteRecursively(destination);
            throw new IOException("This archive does not contain a Bedrock manifest.json");
        }
        File packRoot = manifest.getParentFile();
        if (packRoot != null && !packRoot.equals(destination)) {
            File staging = uniqueDirectory(packsDir, ".pack-import");
            if (!packRoot.renameTo(staging)) {
                deleteRecursively(destination);
                throw new IOException("Could not normalize the pack archive folder");
            }
            deleteRecursively(destination);
            destination = uniqueDirectory(packsDir, safeName(displayName, "BedrockPack"));
            if (!staging.renameTo(destination)) {
                deleteRecursively(staging);
                throw new IOException("Could not finalize the imported pack");
            }
            manifest = new File(destination, "manifest.json");
        }
        try (FileInputStream in = new FileInputStream(manifest);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[16 * 1024];
            int count;
            int total = 0;
            while ((count = in.read(chunk)) != -1) {
                total += count;
                if (total > 512_000) throw new IOException("Pack manifest is larger than 512 KB");
                buffer.write(chunk, 0, count);
            }
            JSONObject json = new JSONObject(new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            String uuid = json.optJSONObject("header") == null ? "" : json.getJSONObject("header").optString("uuid", "");
            if (uuid.trim().isEmpty()) throw new IOException("The pack manifest is missing header.uuid");
        } catch (Exception error) {
            deleteRecursively(destination);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("The Bedrock pack manifest could not be read", error);
        }
        return destination;
    }

    public List<File> listPacks() {
        File[] folders = packsDir.listFiles(File::isDirectory);
        if (folders == null) return Collections.emptyList();
        List<File> list = new ArrayList<>();
        Collections.addAll(list, folders);
        list.sort(Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        return list;
    }

    private static File findServerRoot(File folder, int depth) {
        if (depth < 0 || folder == null || !folder.isDirectory()) return null;
        File executable = new File(folder, "bedrock_server");
        File properties = new File(folder, "server.properties");
        if (executable.isFile() && properties.isFile()) return folder;
        File[] children = folder.listFiles(File::isDirectory);
        if (children == null) return null;
        for (File child : children) {
            File found = findServerRoot(child, depth - 1);
            if (found != null) return found;
        }
        return null;
    }

    private static File findFile(File folder, String name, int depth) {
        if (depth < 0 || folder == null || !folder.isDirectory()) return null;
        File direct = new File(folder, name);
        if (direct.isFile()) return direct;
        File[] children = folder.listFiles(File::isDirectory);
        if (children != null) for (File child : children) {
            File found = findFile(child, name, depth - 1);
            if (found != null) return found;
        }
        return null;
    }

    private static void copyDocumentDirectory(DocumentFile source, File target, ContentResolver resolver) throws IOException {
        DocumentFile[] children = source.listFiles();
        if (children == null) throw new IOException("Could not list the selected folder");
        for (DocumentFile child : children) {
            String name = safeName(child.getName(), "item");
            File destination = new File(target, name);
            if (child.isDirectory()) {
                ensureDirectory(destination);
                copyDocumentDirectory(child, destination, resolver);
            } else if (child.isFile()) {
                try (InputStream input = resolver.openInputStream(child.getUri());
                     FileOutputStream output = new FileOutputStream(destination)) {
                    if (input == null) throw new IOException("Could not read " + name);
                    copy(input, output);
                    output.getFD().sync();
                }
            }
        }
    }

    private static void unzipSafely(InputStream input, File destination) throws IOException {
        String root = destination.getCanonicalPath() + File.separator;
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[32 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new IOException("Archive contains too many files");
                File output = new File(destination, entry.getName().replace('\\', '/'));
                String canonical = output.getCanonicalPath();
                if (!canonical.startsWith(root) && !canonical.equals(destination.getCanonicalPath())) {
                    throw new IOException("Unsafe archive path was blocked");
                }
                if (entry.isDirectory()) {
                    ensureDirectory(output);
                } else {
                    File parent = output.getParentFile();
                    if (parent != null) ensureDirectory(parent);
                    try (FileOutputStream stream = new FileOutputStream(output)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            expanded += count;
                            if (expanded > MAX_EXPANDED_BYTES) throw new IOException("Archive expands beyond the 1.5 GB safety limit");
                            stream.write(buffer, 0, count);
                        }
                        stream.getFD().sync();
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void copy(InputStream input, FileOutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private static File uniqueDirectory(File parent, String base) {
        File candidate = new File(parent, base);
        int index = 2;
        while (candidate.exists()) candidate = new File(parent, base + " " + index++);
        return candidate;
    }

    private static String safeName(String raw, String fallback) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        String safe = raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.equals(".") || safe.equals("..")) return fallback;
        return safe.length() > 70 ? safe.substring(0, 70) : safe;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Could not create " + directory.getName());
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        // Best effort cleanup for incomplete imports.
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
