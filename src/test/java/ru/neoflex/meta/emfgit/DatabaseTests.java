package ru.neoflex.meta.emfgit;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import ru.neoflex.meta.test.Group;
import ru.neoflex.meta.test.TestFactory;
import ru.neoflex.meta.test.User;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static ru.neoflex.meta.emfgit.Database.TYPE_NAME_IDX;
import static ru.neoflex.meta.emfgit.Database.IDX_PATH;

public class DatabaseTests extends TestBase {
    @Before
    public void startUp() throws IOException, GitAPIException {
        database = refreshRatabase();
        database.createBranch("users", "master");
    }

    @Test
    public void createEMFObject() throws IOException, GitAPIException {
        String userId;
        String groupId;
        Group group = TestFactory.eINSTANCE.createGroup();
        try (Transaction tx = database.createTransaction("users")) {
            group.setName("masters");
            ResourceSet resourceSet = database.createResourceSet(tx);
            Resource groupResource = resourceSet.createResource(database.createURI(null, null));
            groupResource.getContents().add(group);
            groupResource.save(null);
            groupId = database.getResourceId(groupResource);
            User user = TestFactory.eINSTANCE.createUser();
            user.setName("Orlov");
            user.setGroup(group);
            Resource userResource = resourceSet.createResource(database.createURI(null, null));
            userResource.getContents().add(user);
            userResource.save(null);
            tx.commit("User Orlov and group masters created", "orlov", "");
            userId = database.getResourceId(userResource);
            Assert.assertNotNull(userId);
        }
        try (Transaction tx = database.createTransaction("users")){
            ResourceSet resourceSet = database.createResourceSet(tx);
            Resource userResource = resourceSet.createResource(database.createURI(userId, null));
            userResource.load(null);
            User user = (User) userResource.getContents().get(0);
            user.setName("Simanihin");
            userResource.save(null);
            tx.commit("User Orlov was renamed to Simanihin", "orlov", "");
        }
        try (Transaction tx = database.createTransaction("users")) {
            User user = TestFactory.eINSTANCE.createUser();
            user.setName("Orlov");
            user.setGroup(group);
            Resource userResource = database.createResource(tx, null, null);
            userResource.getContents().add(user);
            userResource.save(null);
            tx.commit("User Orlov created", "orlov", "");
        }
        try (Transaction tx = database.createTransaction("users")) {
            User user = TestFactory.eINSTANCE.createUser();
            user.setName("Orlov");
            user.setGroup(group);
            Resource userResource = database.createResource(tx, null, null);
            userResource.getContents().add(user);
            try {
                userResource.save(null);
                tx.commit("User Orlov created", "orlov", "");
                Assert.assertTrue(false);
            }
            catch (IllegalArgumentException e) {
                Assert.assertTrue(e.getMessage().startsWith("Duplicate"));
            }
        }
        try (Transaction tx = database.createTransaction("users")){
            ResourceSet resourceSet = database.createResourceSet(tx);
            Resource groupResource = resourceSet.createResource(database.createURI(groupId, null));
            groupResource.load(null);
            try {
                groupResource.delete(null);
                Assert.assertTrue(false);
            }
            catch (IOException e) {
                Assert.assertTrue(e.getMessage().startsWith("Object "));
            }
        }
        try (Transaction tx = database.createTransaction("users")) {
            Path path = tx.getFileSystem().getPath("/", IDX_PATH, TYPE_NAME_IDX);
            Assert.assertEquals(1, database.findByEClass(group.eClass(), null, tx).getResources().size());
            Files.delete(path);
            Assert.assertEquals(0, database.findByEClass(group.eClass(), null, tx).getResources().size());
            database.reindex(tx);
            Assert.assertEquals(1, database.findByEClass(group.eClass(), null, tx).getResources().size());
        }
        try (Transaction tx = database.createTransaction("users")){
            List<Resource> dependent = database.getDependentResources(groupId, tx);
            Assert.assertEquals(2, dependent.size());
            Assert.assertEquals(3, tx.all().size());
            Assert.assertEquals(1, database.findByEClass(group.eClass(), null, tx).getResources().size());
            Assert.assertEquals(1, database.findByEClass(group.eClass(), "masters", tx).getResources().size());
            Assert.assertEquals(0, database.findByEClass(group.eClass(), "UNKNOWN", tx).getResources().size());
            Resource userResource = database.loadResource(userId, tx);
            userResource.delete(null);
            tx.commit("User Simanihin was deleted");
        }
        try (Transaction tx = database.createTransaction("users")){
            List<Resource> dependent = database.getDependentResources(groupId, tx);
            Assert.assertEquals(1, dependent.size());
        }
    }

    @Test
    public void testClassLoader() throws Exception {
        String content = "test content";
        String name = "/ru/neoflex/meta/test/test.txt";
        try(Transaction txw = database.createTransaction("master")) {
            Path path = txw.getFileSystem().getPath(name);
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes());
            txw.commit("written test resource");
        }
        try(Transaction tx = database.createTransaction("master", Transaction.LockType.READ)) {
            byte[] data = tx.withClassLoader(() -> {
                ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                URI uri = classLoader.getResource(name).toURI();
                return Files.readAllBytes(Paths.get(uri));
            });
            Assert.assertEquals(content, new String(data));
        }
    }
}
