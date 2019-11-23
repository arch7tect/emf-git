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
import java.util.HashMap;
import java.util.Map;

public class FinderTests extends TestBase {
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
            Resource groupResource = database.createResource(resourceSet, null, null);
            groupResource.getContents().add(group);
            groupResource.save(null);
            groupId = database.getId(groupResource.getURI());
            user = TestFactory.eINSTANCE.createUser();
            user.setName("Orlov");
            user.setGroup(group);
            Resource userResource = database.createResource(resourceSet, null, null);
            userResource.getContents().add(user);
            userResource.save(null);
            user = TestFactory.eINSTANCE.createUser();
            user.setName("Simanihin");
            user.setGroup(group);
            userResource = database.createResource(resourceSet, null, null);
            userResource.getContents().add(user);
            userResource.save(null);
            tx.commit("Users Orlov, Simanihin and group masters created", "orlov", "");
            userId = database.getResourceId(userResource);
        }
    }

    @Test
    public void exportFind() throws Exception {
        Finder finder;
        Map<String, String> params = new HashMap<>();
        params.put("name", "Orlov");
        try (Transaction tx = database.createTransaction("users")) {
            finder = Finder.create(TestPackage.eINSTANCE.getUser()).execute(tx);
            Assert.assertEquals(2, finder.getResourceSet().getResources().size());
            finder = Finder.create(TestPackage.eINSTANCE.getUser(), params).execute(tx);
            Assert.assertEquals(1, finder.getResourceSet().getResources().size());
            finder = Finder.create(TestPackage.eINSTANCE.getUser());
            finder.selector().with("contents").with("name").put("$regex", ".*Sim.*");
            finder.execute(tx);
            Assert.assertEquals(1, finder.getResourceSet().getResources().size());
        }
    }
}
