package ru.neoflex.meta.emfgit;

import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.Assert;
import org.junit.Test;
import ru.neoflex.meta.test.Group;
import ru.neoflex.meta.test.TestFactory;
import ru.neoflex.meta.test.TestPackage;
import ru.neoflex.meta.test.User;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class PerfTests extends TestBase {
    Database database;
    int nGroups = 5;
    int nUsers = 10;
    int nThreads = 2;
    int nUpdates = 30;
    List<String> groupIds = new ArrayList<>();
    List<String> userIds = new ArrayList<>();

    @Test
    public void fullTest() throws IOException, InterruptedException, GitAPIException {
        database = refreshRatabase();
        database.createBranch("users", "master");
        long start = System.currentTimeMillis();
        for (int i = 0; i < nGroups; ++i) {
            try (Transaction tx = database.createTransaction("users")) {
                Group group = TestFactory.eINSTANCE.createGroup();
                String name = "group_" + i;
                group.setName(name);
                ResourceSet resourceSet = database.createResourceSet(tx);
                Resource groupResource = resourceSet.createResource(database.createURI(null, null));
                groupResource.getContents().add(group);
                groupResource.save(null);
                String groupId = database.getId(groupResource.getURI());
                tx.commit("Group " + name + " created", "orlov", "");
                groupIds.add(groupId);
            }
        }
        long created1 = System.currentTimeMillis();
        for (int i = 0; i < nUsers; ++i) {
            try (Transaction tx = database.createTransaction("users")) {
                Random rand = new Random();
                String groupId = groupIds.get(rand.nextInt(groupIds.size()));
                Resource groupResource = database.loadResource(groupId, tx);
                Group group = (Group) groupResource.getContents().get(0);
                User user = TestFactory.eINSTANCE.createUser();
                String name = "User_" + i;
                user.setName(name);
                user.setGroup(group);
                Resource userResource = database.createResource(tx, null, null);
                userResource.getContents().add(user);
                userResource.save(null);
                tx.commit("User " + name + " created", "orlov", "");
                String userId = database.getId(userResource.getURI());
                userIds.add(userId);
            }
        }
        long created2 = System.currentTimeMillis();
        List<Thread> threads = new ArrayList<>();
        AtomicInteger eCount = new AtomicInteger(0);
        for (int i = 0; i < nThreads; ++i) {
            final int index = i;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Random rand = new Random();
                    for (int j = 0; j < nUpdates; ++j) {
                        String name = "User_" + index + "_" + j;
                        String groupId = groupIds.get(rand.nextInt(groupIds.size()));
                        String userId = userIds.get(rand.nextInt(userIds.size()));
                        try {
                            database.inTransaction("users", Transaction.LockType.WRITE, tx -> {
                                Resource groupResource = database.loadResource(groupId, tx);
                                Group group = (Group) groupResource.getContents().get(0);
                                Resource userResource = database.loadResource(userId, tx);
                                User user = (User) userResource.getContents().get(0);
                                user.setName(name);
                                user.setGroup(group);
                                userResource.save(null);
                                tx.commit("User " + name + " updated", "orlov", "");
                                return null;
                            });
                        } catch (Throwable e) {
                            System.out.println(e.getMessage());
                            eCount.incrementAndGet();
                        }
                    }
                }
            });
            thread.start();
            threads.add(thread);
        }
        for (Thread thread: threads) {
            thread.join();
        }
        database.close();
        long finish = System.currentTimeMillis();
        System.out.println("Created " + nGroups + " groups in " + (created1 - start)/1000 + " sec");
        System.out.println("Created " + nUsers + " users  in " + (created2 - created1)/1000 + " sec");
        System.out.println("Updated " + (nUpdates*nThreads) + " users in " + nThreads + " threads in " + (finish - created2)/1000 + " sec");
        System.out.println("Errors found: " + eCount.get());
        Assert.assertEquals(0, eCount.get());
    }

    public void updateTest() throws IOException, InterruptedException, GitAPIException {
        database = new Database(GITDB, new ArrayList<EPackage>(){{add(TestPackage.eINSTANCE);}});
        readIds();
        long start = System.currentTimeMillis();
        List<Thread> threads = new ArrayList<>();
        AtomicInteger eCount = new AtomicInteger(0);
        for (int i = 0; i < nThreads; ++i) {
            final int index = i;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Random rand = new Random();
                    for (int j = 1000; j < nUpdates+1000; ++j) {
                        try (Transaction tx = database.createTransaction("users")) {
                            String groupId = groupIds.get(rand.nextInt(groupIds.size()));
                            Resource groupResource = database.loadResource(groupId, tx);
                            Group group = (Group) groupResource.getContents().get(0);
                            String userId = userIds.get(rand.nextInt(userIds.size()));
                            Resource userResource = database.loadResource(userId, tx);
                            User user = (User) userResource.getContents().get(0);
                            String name = "User_" + index + "_" + j;
                            user.setName(name);
                            user.setGroup(group);
                            userResource.save(null);
                            tx.commit("User " + name + " updated", "orlov", "");
                        } catch (Throwable e) {
                            e.printStackTrace();
                            eCount.incrementAndGet();
                        }
                    }
                }
            });
            thread.start();
            threads.add(thread);
        }
        for (Thread thread: threads) {
            thread.join();
        }
        database.close();
        long finish = System.currentTimeMillis();
        System.out.println("Updated " + (nUpdates*nThreads) + " users in " + nThreads + " threads in " + (finish - start)/1000 + " sec");
        System.out.println("Errors found: " + eCount.get());
    }

    private void readIds() throws IOException, GitAPIException {
        groupIds.clear();
        try (Transaction tx = database.createTransaction("users", Transaction.LockType.READ)) {
            for (Resource resource: database.findByEClass(TestPackage.eINSTANCE.getGroup(), null, tx).getResources()) {
                groupIds.add(database.getId(resource.getURI()));
            }
        }
        userIds.clear();
        try (Transaction tx = database.createTransaction("users", Transaction.LockType.READ)) {
            for (Resource resource: database.findByEClass(TestPackage.eINSTANCE.getUser(), null, tx).getResources()) {
                userIds.add(database.getId(resource.getURI()));
            }
        }
    }
}
