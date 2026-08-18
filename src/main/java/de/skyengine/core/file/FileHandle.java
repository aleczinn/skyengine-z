package de.skyengine.core.file;

import de.skyengine.core.resource.Resources;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public class FileHandle {

    private final File file;
    private final FileType type;
    /** Logischer Legacy-Pfad fuer RESOURCE-Handles (z.B. game/textures/...). */
    private final String resourcePath;

    private final Logger logger = LogManager.getLogger(FileHandle.class.getName());

    public FileHandle(String path) {
        this.file = new File(path);
        this.type = FileType.ABSOLUTE;
        this.resourcePath = null;
    }

    public FileHandle(String path, FileType type) {
        this.type = type;
        this.resourcePath = type == FileType.RESOURCE ? path.replace('\\', '/') : null;
        switch (type) {
            case EXTERNAL -> {
                this.file = new File(Files.EXTERNAL_PATH, path);
            }
            case RESOURCE -> {
                this.file = new File(Files.RESOURCES_PATH, path);
            }
            case PROJECT -> {
                this.file = new File(Files.PROJECT_PATH, path);
            }
            default -> {
                this.file = new File(path);
            }
        }
    }

    public FileHandle(File file) {
        this.file = file;
        this.type = FileType.ABSOLUTE;
        this.resourcePath = null;
    }

    /**
     * @return the path of the file as specified on construction, e.g.
     */
    public String path() {
        return this.file.getPath().replace('\\', '/');
    }

    /**
     * @return the name of the file, without any parent paths.
     */
    public String name() {
        return this.file.getName();
    }

    /**
     * @return the file extension (without the dot) or an empty string if the file name doesn't contain a dot.
     */
    public String extension() {
        String name = this.file.getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex == -1) return "";
        return name.substring(dotIndex + 1);
    }

    /**
     * @return the name of the file, without parent paths or the extension.
     */
    public String nameWithoutExtension() {
        String name = this.file.getName();
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex == -1) return name;
        return name.substring(0, dotIndex);
    }

    /**
     * @return the path and filename without the extension, e.g. dir/dir2/file.png -> dir/dir2/file. backward slashes will be returned as forward slashes.
     */
    public String pathWithoutExtension() {
        String path = this.file.getPath().replace('\\', '/');
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex == -1) return path;
        return path.substring(0, dotIndex);
    }

    public boolean exists() {
        if (this.type == FileType.RESOURCE) return Resources.get().exists(this.resourcePath);
        return this.file.exists();
    }

    public FileHandle parent() {
        File parent = this.file.getParentFile();
        if (parent == null) {
            if (this.type == FileType.ABSOLUTE) {
                parent = new File("/");
            } else {
                parent = new File("");
            }
        }
        return new FileHandle(parent.getPath(), FileType.ABSOLUTE);
    }

    /**
     * Deletes this file or empty directory and returns success. Will not delete a directory that has children.
     *
     * @throws RuntimeException if this file handle is a {@link FileType#PROJECT} or {@link FileType#RESOURCE} file.
     */
    public boolean delete() {
        if (this.type == FileType.PROJECT) throw new RuntimeException("Cannot delete a project file: " + this.file);
        if (this.type == FileType.RESOURCE) throw new RuntimeException("Cannot delete an resource file: " + this.file);
        return this.file.delete();
    }

    /**
     * @throws RuntimeException if this file handle is a {@link FileType#PROJECT} or {@link FileType#RESOURCE} file.
     */
    public boolean mkdirs() {
        if (this.type == FileType.PROJECT)
            throw new RuntimeException("Cannot mkdirs with a project file: " + this.file);
        if (this.type == FileType.RESOURCE)
            throw new RuntimeException("Cannot mkdirs with an resource file: " + this.file);
        return this.file.mkdirs();
    }

    /**
     * @return true if this file is a directory.
     */
    public boolean isDirectory() {
        return this.file.isDirectory();
    }

    /**
     * @return the file content as a string.
     */
    public String readString() {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader bufferedReader = new BufferedReader(this.reader())) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } catch (IOException e) {
            this.logger.fatal("Error reading file.", new RuntimeException(e));
        }
        return builder.toString();
    }

    /**
     * @return the file content as a string.
     */
    public List<String> readList() {
        List<String> list = new ArrayList<>();
        try (BufferedReader bufferedReader = new BufferedReader(this.reader())) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                list.add(line);
            }
        } catch (IOException e) {
            this.logger.fatal("Error reading file.", new RuntimeException(e));
        }
        return list;
    }

    /**
     * Returns a stream for reading this file as bytes.
     *
     * @throws RuntimeException if the file handle represents a directory, doesn't exist, or could not be read.
     */
    public InputStream read() {
        if (this.type == FileType.RESOURCE) {
            try {
                return Resources.get().open(this.resourcePath);
            } catch (IOException e) {
                throw new RuntimeException("File not found: " + this.resourcePath + " (" + type + ")", e);
            }
        }
        if (this.type == FileType.PROJECT && exists()) {
            InputStream stream = FileHandle.class.getResourceAsStream("/" + file.getPath().replace('\\', '/'));
            if (stream != null) return stream;
        }

        try {
            return new FileInputStream(getFile());
        } catch (Exception ex) {
            if (getFile().isDirectory()) {
                throw new RuntimeException("Cannot open a stream to a directory: " + file + " (" + type + ")", ex);
            }
            throw new RuntimeException("Error reading file: " + file + " (" + type + ")", ex);
        }
    }

    /**
     * Returns a reader for reading this file as characters the platform's default charset.
     *
     * @throws RuntimeException if the file handle represents a directory, doesn't exist, or could not be read.
     */
    public Reader reader() {
        return new InputStreamReader(read());
    }

    /**
     * Returns a buffered stream for reading this file as bytes.
     *
     * @throws RuntimeException if the file handle represents a directory, doesn't exist, or could not be read.
     */
    public BufferedInputStream read(int bufferSize) {
        return new BufferedInputStream(read(), bufferSize);
    }

    /**
     * Returns a stream for writing to this file. Parent directories will be created if necessary.
     *
     * @param append If false, this file will be overwritten if it exists, otherwise it will be appended.
     * @throws RuntimeException if this file handle represents a directory, if it is a {@link FileType#PROJECT} or
     *                          {@link FileType#RESOURCE} file, or if it could not be written.
     */
    public OutputStream write(boolean append) {
        if (this.type == FileType.PROJECT) {
            throw new RuntimeException("Cannot write to a project file: " + this.file);
        }

        if (this.type == FileType.RESOURCE) {
            throw new RuntimeException("Cannot write to an resource file: " + this.file);
        }

        this.parent().mkdirs();

        try {
            return new FileOutputStream(this.getFile(), append);
        } catch (Exception ex) {
            if (getFile().isDirectory()) {
                throw new RuntimeException("Cannot open a stream to a directory: " + file + " (" + type + ")", ex);
            }
            throw new RuntimeException("Error writing file: " + file + " (" + type + ")", ex);
        }
    }

    public File getFile() {
        return file;
    }

    /** Logischer Ressourcenpfad oder {@code null} fuer normale Dateien. */
    public String resourcePath() {
        return this.resourcePath;
    }

    public FileType getType() {
        return type;
    }

    @Override
    public String toString() {
        return String.format(
                "FileHandle={\n  %s\n  %s\n}",
                this.file,
                this.type
        );
    }
}
