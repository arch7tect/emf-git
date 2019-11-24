package ru.neoflex.meta.emfgit;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.jgit.api.errors.GitAPIException;
import ru.neoflex.meta.test.TestPackage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public class TestBase {
    public static final String GITDB = "test-emf-git";
    Database database;

    public static boolean deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        return directoryToBeDeleted.delete();
    }

    public static Database getDatabase() throws IOException, GitAPIException {
        return new Database(getDatabaseFile().getAbsolutePath(), new ArrayList<EPackage>(){{add(TestPackage.eINSTANCE);}});
    }

    public static File getDatabaseFile() throws IOException, GitAPIException {
        return new File(System.getProperty("user.home") + "/.gitdb", GITDB);
    }

    public static Database refreshRatabase() throws IOException, GitAPIException {
        deleteDirectory(getDatabaseFile());
        return getDatabase();
    }
}
