# Use GIT as the storage for EMF (Eclipse Modelling Framework) objects 
let's say you have such a model (in terms of xcore language):
```xcore
package ru.neoflex.meta.test

class Group {
    String name
}

class User {
    String name
    refers Group group
}

```

Now, ctreate database and save objects:
```java
Database database = new Database("gitdb", new ArrayList<EPackage>(){{add(TestPackage.eINSTANCE);}});
try (Transaction tx = database.createTransaction("users")) {
    Group group = TestFactory.eINSTANCE.createGroup();
    group.setName("masters");
    ResourceSet resourceSet = database.createResourceSet(tx);
    Resource groupResource = resourceSet.createResource(database.createURI(null, null));
    groupResource.getContents().add(group);
    groupResource.save(null);
    String groupId = database.getResourceId(groupResource);
    User user = TestFactory.eINSTANCE.createUser();
    user.setName("arch7tect");
    user.setGroup(group);
    Resource userResource = resourceSet.createResource(database.createURI(null, null));
    userResource.getContents().add(user);
    userResource.save(null);
    tx.commit("User arch7tect and group masters created", "arch7tect", "");
    String userId = database.getResourceId(userResource);
}
```

Let's see file system
```shell script
cd gitdb
ls
```
Output:
```
.git
```
```shell script
git checkout users
ls
```
Output:
```
.git db
```
Load resource by id
```java
try (Transaction tx = database.createTransaction("users")){
    ResourceSet resourceSet = database.createResourceSet(tx);
    Resource userResource = resourceSet.createResource(database.createURI(userId, null));
    userResource.load(null);
    User user = (User) userResource.getContents().get(0);
    Assert.assertEquals("masters", user.getGroup().getName*());
}
```
Find resource
```java
try (Transaction tx = database.createTransaction("users")){
    Assert.assertEquals(1, database.findByEClass(group.eClass(), null, tx).getResources().size());
    Assert.assertEquals(1, database.findByEClass(group.eClass(), "masters", tx).getResources().size());
    Assert.assertEquals(0, database.findByEClass(group.eClass(), "UNKNOWN", tx).getResources().size());
}
```
More find resources
```java
try (Transaction tx = database.createTransaction("users")) {
    List<Resource> dependent = database.getDependentResources(groupId, tx);
    Assert.assertEquals(1, dependent.size());

    Assert.assertEquals(2, tx.all().size());

    Finder finder = Finder.create(TestPackage.eINSTANCE.getUser());
    finder.selector().with("contents").with("name").put("$regex", ".*rch7.*");
    finder.execute(tx);
    Assert.assertEquals(1, finder.getResourceSet().getResources().size());
}
```
Delete resource
```java
try (Transaction tx = database.createTransaction("users")){
    Resource userResource = database.loadResource(userId, tx);
    userResource.delete(null);
    tx.commit("User arch7tect was deleted");
}
```
# Export/import objects
Sometimes, we  need to partly transfer out metadata to another environment 
(dev-test-prod pipeline for example). To merge new objects
with existing one we can not rely on internal ids. But,
if top level objects contains unique qualified name feature,
we can do it as simple as
```java
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
```
or use zipped stream/file
```java
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
    tx.commit("Database was restored from zip archive");
    Assert.assertEquals(2, tx.all().size());
}
```
# Use GIT as the versioned resources store
```java
// create resource
String content = "test content";
String name = "/ru/neoflex/meta/test/test.txt";
try(Transaction txw = database.createTransaction("master")) {
    Path path = txw.getFileSystem().getPath(name);
    Files.createDirectories(path.getParent());
    Files.write(path, content.getBytes());
    txw.commit("written test resource");
}
// load resource
try(Transaction tx = database.createTransaction("master", Transaction.LockType.READ)) {
    byte[] data = tx.withClassLoader(() -> {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        URI uri = classLoader.getResource(name).toURI();
        return Files.readAllBytes(Paths.get(uri));
    });
    Assert.assertEquals(content, new String(data));
}
```
# Libraries used
* the gorgeous library https://github.com/beijunyi/ParallelGit 
  has been included with minor modifications (uri format in particular)
* https://github.com/marschall/path-classloader has been included to use 
git file system as a classpath resources