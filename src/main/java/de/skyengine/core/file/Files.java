package de.skyengine.core.file;


import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.*;
import java.nio.file.attribute.FileTime;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Files {

    public static final String EXTERNAL_PATH = System.getProperty("user.home") + File.separator;
    public static final String PROJECT_PATH = new File("").getAbsolutePath() + File.separator;
    public static final String RESOURCES_PATH = new File(PROJECT_PATH, "src/main/resources").getAbsolutePath() + File.separator;

    private final Logger logger = LogManager.getLogger(Files.class.getName());

    public FileHandle resource(String path) {
        return new FileHandle(path, FileType.RESOURCE);
    }

    public FileHandle project(String path) {
        return new FileHandle(path, FileType.PROJECT);
    }

    public FileHandle absolute(String path) {
        return new FileHandle(path, FileType.ABSOLUTE);
    }

    public boolean createFile(String path) {
        try {
            File file = new File(path);
            if (file.exists()) {
                this.logger.error("File " + path + " already exists.");
            } else {
                return file.createNewFile();
            }
        } catch (Exception e) {
            this.logger.fatal("File " + path + " could not be created.", e);
        }
        return false;
    }

    public boolean createFile(File file) {
        try {
            if (file.exists()) {
                this.logger.error("File " + file + " already exists.");
            } else {
                return file.createNewFile();
            }
        } catch (Exception e) {
            this.logger.fatal("File " + file.getPath() + " could not be created.", e);
        }
        return false;
    }

    public boolean createFile(FileHandle handle) {
        try {
            if (handle.exists()) {
                this.logger.error("File " + handle + " already exists.");
            } else {
                return handle.getFile().createNewFile();
            }
        } catch (Exception e) {
            this.logger.fatal("File " + handle.path() + " could not be created.", e);
        }
        return false;
    }

    public void zipFile(File file) {
        try {
            FileOutputStream fos = new FileOutputStream(file.getPath() + ".zip");
            ZipOutputStream zos = new ZipOutputStream(fos);

            zos.putNextEntry(new ZipEntry(file.getName()));

            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            zos.write(bytes, 0, bytes.length);
            zos.closeEntry();
            zos.close();
            fos.close();
        } catch (FileNotFoundException e) {
            this.logger.error("The file " + file.getAbsolutePath() + " does not exist!");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void zipFile(File file, String destination) {
        try {
            FileOutputStream fos = new FileOutputStream(destination + ".zip");
            ZipOutputStream zos = new ZipOutputStream(fos);

            zos.putNextEntry(new ZipEntry(file.getName()));

            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            zos.write(bytes, 0, bytes.length);
            zos.closeEntry();
            zos.close();
            fos.close();

        } catch (FileNotFoundException e) {
            this.logger.error("The file " + file.getAbsolutePath() + " does not exist!");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
        byte[] bytesIn = new byte[4069];
        int read = 0;
        while ((read = zipIn.read(bytesIn)) != -1) {
            bos.write(bytesIn, 0, read);
        }
        bos.close();
    }

    public FileTime getLastModifiedTime(File file) {
        if (!file.exists()) return null;
        try {
            return java.nio.file.Files.getLastModifiedTime(file.toPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void clearFileContent(File file) {
        if (file.exists()) {
            try {
                new FileWriter(file, false).close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public String getStackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);
        return writer.toString();
    }
}
