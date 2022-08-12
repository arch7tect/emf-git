package ru.neoflex.meta.emfgit;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Exporter {
    public static final String NAME = "name";
    public static final String XMI = ".xmi";
    public static final String REFS = ".refs";
    public static final String E_OBJECT = "e-object";
    public static final String REF_OBJECT = "ref-object";
    public static final String REFERENCE = "reference";
    public static final String FEATURE = "feature";
    public static final String FRAGMENT = "fragment";
    public static final String INDEX = "index";
    public static final String E_CLASS = "e-class";
    public static final String Q_NAME = "q-name";

    Database database;

    public Exporter(Database database) {
        this.database = database;
    }

    public byte[] exportEObjectWithoutExternalRefs(EObject eObject) throws IOException {
        ResourceSet resourceSet = database.createResourceSet(null);
        Resource resource = resourceSet.createResource(eObject.eResource().getURI());
        EObject copyObject = EcoreUtil.copy(eObject);
        resource.getContents().add(copyObject);
        unsetExternalReferences(copyObject);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        resource.save(os, null);
        return os.toByteArray();
    }

    public void unsetExternalReferences(EObject eObject) {
        Map<EObject, Collection<EStructuralFeature.Setting>> crs = EcoreUtil.ExternalCrossReferencer.find(Collections.singleton(eObject));
        for (EObject refObject: crs.keySet()) {
            for (EStructuralFeature.Setting setting: crs.get(refObject)) {
                setting.unset();
            }
        }
    }


    public void exportEObject(EObject eObject, Path path) throws IOException, ParserConfigurationException, TransformerException {
        EClass eClass = eObject.eClass();
//        EPackage ePackage = eClass.getEPackage();
        EStructuralFeature nameAttribute = database.getQNameFeature(eClass);
        if (nameAttribute != null) {
            String name = (String) eObject.eGet(nameAttribute);
            if (name != null && name.length() > 0) {
                byte[] bytes = exportEObjectWithoutExternalRefs(eObject);
//                String fileName = ePackage.getName() + "_" + eClass.getName() + "_" + name;
                String fileName = database.getId(eObject.eResource().getURI());
                Path filePath = path.resolve(fileName + XMI);
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, bytes);
                byte[] refsBytes = exportExternalReferences(eObject);
                if (refsBytes != null) {
                    Path refsPath = path.resolve(fileName + REFS);
                    Files.createDirectories(refsPath.getParent());
                    Files.write(refsPath, refsBytes);
                }
            }
        }
    }

    public void exportResourceSet(ResourceSet resourceSet, Path path) throws IOException, ParserConfigurationException, TransformerException {
        for (Resource resource: resourceSet.getResources()) {
            for (EObject eObject: resource.getContents()) {
                exportEObject(eObject, path);
            }
        }
    }

    public void zip(ResourceSet resourceSet, OutputStream outputStream) throws IOException {
        List<Resource> resources = resourceSet.getResources();
        zip(resources, outputStream);
    }

    public void zip(List<Resource> resources, OutputStream outputStream) throws IOException {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);) {
            for (Resource resource: resources) {
                zipResource(zipOutputStream, resource);
            }
            for (Resource resource: resources) {
                zipResourceReferences(zipOutputStream, resource);
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public void zipAll(String branch, OutputStream outputStream) throws Exception {
        database.inTransaction(branch, Transaction.LockType.READ, tx -> {
            List<EntityId> all = tx.all();
            try (ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream);) {
                for (EntityId entityId: all) {
                    Resource resource = database.loadResource(entityId.getId(), tx);
                    zipResource(zipOutputStream, resource);
                }
                for (EntityId entityId: all) {
                    Resource resource = database.loadResource(entityId.getId(), tx);
                    zipResourceReferences(zipOutputStream, resource);
                }
            }
            return null;
        });
    }

    public void zipResourceReferences(ZipOutputStream zipOutputStream, Resource resource) throws IOException, ParserConfigurationException, TransformerException {
        for (EObject eObject: resource.getContents()) {
            EClass eClass = eObject.eClass();
            EPackage ePackage = eClass.getEPackage();
            EStructuralFeature nameAttribute = database.getQNameFeature(eClass);
            if (nameAttribute != null) {
                String name = (String) eObject.eGet(nameAttribute);
                if (name != null && name.length() > 0) {
//                    String fileName = ePackage.getName() + "_" + eClass.getName() + "_" + name;
                    String fileName = database.getId(resource.getURI());
                    byte[] refsBytes = exportExternalReferences(eObject);
                    if (refsBytes != null) {
                        ZipEntry refsEntry = new ZipEntry(fileName + REFS);
                        zipOutputStream.putNextEntry(refsEntry);
                        zipOutputStream.write(refsBytes);
                        zipOutputStream.closeEntry();
                    }
                }
            }
        }
    }

    public void zipResource(ZipOutputStream zipOutputStream, Resource resource) throws IOException {
        for (EObject eObject: resource.getContents()) {
            EClass eClass = eObject.eClass();
            EPackage ePackage = eClass.getEPackage();
            EStructuralFeature nameAttribute = database.getQNameFeature(eClass);
            if (nameAttribute != null) {
                String name = (String) eObject.eGet(nameAttribute);
                if (name != null && name.length() > 0) {
//                    String fileName = ePackage.getName() + "_" + eClass.getName() + "_" + name;
                    String fileName = database.getId(resource.getURI());
                    byte[] bytes = exportEObjectWithoutExternalRefs(eObject);
                    ZipEntry zipEntry = new ZipEntry(fileName + XMI);
                    zipOutputStream.putNextEntry(zipEntry);
                    zipOutputStream.write(bytes);
                    zipOutputStream.closeEntry();
                }
            }
        }
    }

    public int unzip(InputStream inputStream, Transaction tx) throws IOException {
        int entityCount = 0;
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream);) {
            ZipEntry zipEntry = zipInputStream.getNextEntry();
            while (zipEntry != null) {
                if (!zipEntry.isDirectory()) {
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int length;
                    while ((length = zipInputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, length);
                    }
                    if (zipEntry.getName().endsWith(XMI)) {
                        importResource(outputStream.toByteArray(), tx, zipEntry.getName().substring(0, zipEntry.getName().length() - XMI.length()));
                        ++entityCount;
                    }
                    else if (zipEntry.getName().endsWith(REFS)) {
                        importExternalReferences(outputStream.toByteArray(), tx);
                    }
                }
                zipEntry = zipInputStream.getNextEntry();
            }
        } catch (Exception e) {
            throw new IOException(e);
        }
        return entityCount;
    }

    public void unzip(Path zipFile, Transaction tx) throws Exception {
        Map<String, Object> env = new HashMap<>();
        //env.put("create", "true");
        env.put("useTempFile", Boolean.TRUE);
        java.net.URI uri = java.net.URI.create("jar:" + zipFile.toUri());
        FileSystem fileSystem = FileSystems.newFileSystem(uri, env);
        Iterable<Path> roots = fileSystem.getRootDirectories();
        Path root = roots.iterator().next();
        importPath(root, tx);
    }

    public void exportAll(String branch, Path path) throws Exception {
        List<EntityId> all = database.inTransaction(branch, Transaction.LockType.READ, Transaction::all);
        for (EntityId entityId: all) {
            database.inTransaction(branch, Transaction.LockType.READ, tx -> {
                Resource resource = database.loadResource(entityId.getId(), tx);
                for (EObject eObject: resource.getContents()) {
                    exportEObject(eObject, path);
                }
                return resource;
            });
        }
    }

    public void importPath(Path path, Transaction tx) throws Exception {
        List<Path> xmiPaths = Files.walk(path).filter(Files::isRegularFile).filter(file -> file.getFileName().toString().endsWith(XMI)).collect(Collectors.toList());
        for (Path xmiPath : xmiPaths) {
            byte[] content = Files.readAllBytes(xmiPath);
            importResource(content, tx, path.relativize(xmiPath).toString());
        }
        List<Path> refsPaths = Files.walk(path).filter(Files::isRegularFile).filter(file -> file.getFileName().toString().endsWith(REFS)).collect(Collectors.toList());
        for (Path refsPath : refsPaths) {
            byte[] content = Files.readAllBytes(refsPath);
            importExternalReferences(content, tx);
        }
    }

    public EObject importResource(byte[] image, Transaction tx, String id) throws IOException {
        Resource resource = database.createResource(tx, id);
        resource.load(new ByteArrayInputStream(image), null);
        EObject eObject = resource.getContents().get(0);
        EClass eClass = eObject.eClass();
        EStructuralFeature nameFeature = database.getQNameFeature(eClass);
        if (nameFeature == null) {
            throw new IOException("Qualified name not found in " + eClass2String(eClass));
        }
        String name = (String) eObject.eGet(nameFeature);
        ResourceSet existentRS = database.findByEClass(eClass, name, tx);
        if (existentRS.getResources().size() > 0) {
            Resource r = existentRS.getResources().get(0);
            resource.setURI(r.getURI());
            resource.setTimeStamp(r.getTimeStamp());
        }
        resource.save(null);
        return eObject;
    }

    public String eClass2String(EClass eClass) {
        return EcoreUtil.getURI(eClass).toString();
    }

    public EClass string2EClass(String uri) {
        return (EClass) database.createResourceSet(null).getEObject(URI.createURI(uri), false);
    }


    private static class Setting {
        EObject referenceeObject;
        EReference eReference;
        EObject refObject;
        int index;
    }
    protected EObject elementToObject(Element element, Transaction tx) throws Exception {
        ResourceSet rs = tx.getDatabase().createResourceSet(tx);
        String classUri = element.getAttribute(E_CLASS);
        EClass eClass = (EClass) rs.getEObject(URI.createURI(classUri), false);
        String qName = element.getAttribute(Q_NAME);
        String fragment = element.getAttribute(FRAGMENT);
        List<EObject> eObjects = tx.getDatabase().findByEClass(eClass, qName, tx)
                .getResources().stream()
                .flatMap(r -> r.getContents().stream())
                .map(eObject -> fragment.length() == 0 ? eObject : EcoreUtil.getEObject(eObject, fragment))
                .filter(eObject -> eObject != null)
                .collect(Collectors.toList());
        if (eObjects.size() == 0) {
            throw new IllegalArgumentException(String.format("EObject not found: %s[%s/$s]",
                    classUri, qName, fragment));
        }
        return eObjects.get(0);
    }

    private EObject importExternalReferences(byte[] bytes, Transaction tx) throws Exception {
        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = builderFactory.newDocumentBuilder();
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
        Document document = documentBuilder.parse(stream);
        Element rootElement = document.getDocumentElement();
        EObject eObject = elementToObject(rootElement, tx);
        unsetExternalReferences(eObject);
        List<Setting> settings = new ArrayList<>();
        NodeList refObjectNodes = rootElement.getElementsByTagName(REF_OBJECT);
        for (int i = 0; i < refObjectNodes.getLength(); ++i) {
            Element refObjectNode = (Element) refObjectNodes.item(i);
            EObject refObject = elementToObject(refObjectNode, tx);
            NodeList refNodes = refObjectNode.getElementsByTagName(REFERENCE);
            for (int j = 0; j < refNodes.getLength(); ++j) {
                Element refNode = (Element) refNodes.item(j);
                Setting setting = new Setting();
                setting.refObject = refObject;
                String fragment = refNode.getAttribute(FRAGMENT);
                setting.referenceeObject = fragment == null || fragment.length() == 0 ? eObject : EcoreUtil.getEObject(eObject, fragment);
                String feature = refNode.getAttribute(FEATURE);
                setting.eReference = (EReference) setting.referenceeObject.eClass().getEStructuralFeature(feature);
                int index = Integer.parseInt(refNode.getAttribute(INDEX));
                setting.index = index;
                settings.add(setting);
            }
        }
        settings.sort(Comparator.comparing(s -> s.index));
        for (Setting setting : settings) {
            if (setting.eReference.isMany()) {
                EList eList = (EList) setting.referenceeObject.eGet(setting.eReference);
                eList.add(setting.index >= 0 ? setting.index : eList.size(), setting.refObject);
            } else {
                setting.referenceeObject.eSet(setting.eReference, setting.refObject);
            }
        }
        eObject.eResource().save(null);
        return eObject;
    }

    public Element objectToElement(Document document, EObject eObject, String tag) {
        Element element = document.createElement(tag);
        EObject rootContainer = EcoreUtil.getRootContainer(eObject);
        String fragment = EcoreUtil.getRelativeURIFragmentPath(rootContainer, eObject);
        String qName = (String) rootContainer.eGet(database.checkAndGetQNameFeature(rootContainer.eClass()));
        element.setAttribute(E_CLASS, EcoreUtil.getURI(rootContainer.eClass()).toString());
        element.setAttribute(Q_NAME, qName);
        element.setAttribute(FRAGMENT, fragment);
        return element;
    }

    public byte[] exportExternalReferences(EObject eObject) throws ParserConfigurationException, TransformerException {
        Map<EObject, Collection<EStructuralFeature.Setting>> crs = EcoreUtil.ExternalCrossReferencer.find(Collections.singleton(eObject));
        if (crs.size() == 0) {
            return null;
        }
        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = builderFactory.newDocumentBuilder();
        Document document = documentBuilder.newDocument();
        Element rootElement = objectToElement(document, eObject, E_OBJECT);
        document.appendChild(rootElement);
        for (EObject refObject : crs.keySet()) {
            Element referenceObjectElement = objectToElement(document, refObject, REF_OBJECT);
            rootElement.appendChild(referenceObjectElement);
            for (EStructuralFeature.Setting setting : crs.get(refObject)) {
                Element referenceElement = document.createElement(REFERENCE);
                referenceObjectElement.appendChild(referenceElement);
                referenceElement.setAttribute(FEATURE, setting.getEStructuralFeature().getName());
                EObject child = setting.getEObject();
                String fragment = EcoreUtil.getRelativeURIFragmentPath(eObject, child);
                referenceElement.setAttribute(FRAGMENT, fragment);
                EStructuralFeature sf = setting.getEStructuralFeature();
                int index = -1;
                if (sf.isMany()) {
                    EList eList = (EList) child.eGet(sf);
                    index = eList.indexOf(refObject);
                }
                referenceElement.setAttribute(INDEX, String.valueOf(index));
            }
        }
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setAttribute("indent-number", 2);
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        DOMSource source = new DOMSource(document);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        StreamResult result = new StreamResult(stream);
        transformer.transform(source, result);
        return stream.toByteArray();
    }
}
