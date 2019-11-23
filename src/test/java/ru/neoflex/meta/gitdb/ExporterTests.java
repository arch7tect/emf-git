package ru.neoflex.meta.gitdb;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import ru.neoflex.meta.test.Group;
import ru.neoflex.meta.test.TestFactory;
import ru.neoflex.meta.test.TestPackage;
import ru.neoflex.meta.test.User;

import java.nio.file.Files;
import java.nio.file.Path;

public class ExporterTests extends TestBase {
    User user;
    String userId;
    Group group;
    String groupId;
    Exporter exporter;

    @Before
    public void startUp() throws Exception {
        database = refreshRatabase();
        database.createBranch("users", "master");
        createEMFObject();
        exporter = new Exporter(database);
    }

    public void createEMFObject() throws Exception {
        group = TestFactory.eINSTANCE.createGroup();
        try (Transaction tx = database.createTransaction("users")) {
            group.setName("masters");
            ResourceSet resourceSet = database.createResourceSet(tx);
            Resource groupResource = resourceSet.createResource(database.createURI(null, null));
            groupResource.getContents().add(group);
            groupResource.save(null);
            groupId = database.getId(groupResource.getURI());
            user = TestFactory.eINSTANCE.createUser();
            user.setName("Orlov");
            user.setGroup(group);
            Resource userResource = resourceSet.createResource(database.createURI(null, null));
            userResource.getContents().add(user);
            userResource.save(null);
            tx.commit("User Orlov and group masters created", "orlov", "");
            userId = database.getResourceId(userResource);
        }
    }

    @Test
    public void testEclass2String() throws Exception {
        String uri = exporter.eClass2String(user.eClass());
        Assert.assertEquals(user.eClass(), exporter.string2EClass(uri));
    }

    @Test
    public void exportTest() throws Exception {
        try (Transaction tx = database.createTransaction("users")) {
            Path path = tx.getFileSystem().getPath("/export");
            Files.createDirectories(path);
            exporter.exportAll("users", path);
            tx.commit("Export all objects");
            Assert.assertEquals(3, Files.walk(path).filter(Files::isRegularFile).count());
        }
        try (Transaction tx = database.createTransaction("users")) {
            Path path = tx.getFileSystem().getPath("/db");
            database.deleteRecursive(path);
            tx.commit("Database was deleted");
            Assert.assertEquals(0, tx.all().size());
        }
        try (Transaction tx = database.createTransaction("users")) {
            Path path = tx.getFileSystem().getPath("/export");
            exporter.importPath(path, tx);
            tx.commit("Database was restored");
            Assert.assertEquals(2, tx.all().size());
        }
        try (Transaction tx = database.createTransaction("users")) {
            Path path = tx.getFileSystem().getPath("/export");
            exporter.importPath(path, tx);
            tx.commit("Database was restored with existing objects");
            Assert.assertEquals(2, tx.all().size());
        }
    }

    @Test
    public void zipTest() throws Exception {
        try (Transaction tx = database.createTransaction("users")) {
            Path path = tx.getFileSystem().getPath("/zip/all.zip");
            Files.createDirectories(path.getParent());
            ResourceSet resourceSet = database.createResourceSet(tx);
            for (EntityId entityId: tx.all()) {
                Resource resource = resourceSet.createResource(database.createURI(entityId.getId(), null));
                resource.load(null);
            }
            exporter.zip(resourceSet, Files.newOutputStream(path));
            tx.commit("Zip all objects");
            Assert.assertEquals(1, Files.walk(path).filter(Files::isRegularFile).count());
        }
        try (Transaction tx = database.createTransaction("users")) {
            Path path = tx.getFileSystem().getPath("/db");
            database.deleteRecursive(path);
            tx.commit("Database was deleted");
            Assert.assertEquals(0, tx.all().size());
        }
        try (Transaction tx = database.createTransaction("users")) {
            Path path = tx.getFileSystem().getPath("/zip/all.zip");
            exporter.unzip(Files.newInputStream(path), tx);
            tx.commit("Database was restored with zip archive");
            Assert.assertEquals(2, tx.all().size());
        }
        try (Transaction tx = database.createTransaction("users")) {
            Path path = tx.getFileSystem().getPath("/zip/all.zip");
            exporter.unzip(path, tx);
            tx.commit("Database was restored 2 time with zip archive");
            Assert.assertEquals(2, tx.all().size());
        }
    }
}
