package ru.neoflex.meta.emfgit;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.URIConverter;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.eclipse.jgit.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Map;

public class GitOutputStream extends ByteArrayOutputStream implements URIConverter.Saveable {
    private GitHandler handler;
    private URI uri;
    private Map<?, ?> options;

    static SecureRandom prng;

    static {
        try {
            prng = SecureRandom.getInstance("SHA1PRNG");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getRandomId(int length) {
        byte[] bytes = new byte[length];
        prng.nextBytes(bytes);
        return hex(bytes);
    }

//    public static String getUUID() {
//        byte[] bytes = new byte[16];
//        EcoreUtil.generateUUID(bytes);
//        return hex(bytes);
//    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private static String hex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public GitOutputStream(GitHandler handler, URI uri, Map<?, ?> options) {
        this.handler = handler;
        this.uri = uri;
        this.options = options;
    }

    @Override
    public void saveResource(Resource resource) throws IOException {
        Transaction transaction = handler.getTransaction();
        Database db = transaction.getDatabase();
        String id = db.getResourceId(resource);
        EntityId oldEntityId = new EntityId(id, resource.getTimeStamp());
        boolean isExists = id != null && transaction.isResourceExists(oldEntityId);
        Resource oldResource = null;
        if (isExists) {
            Entity oldEntity = transaction.load(oldEntityId);
            oldResource = db.entityToResource(transaction, oldEntity);
        }
        else {
            if (id== null) {
                id = getRandomId(2) + "/" + getRandomId(14);
            }
            resource.setURI(db.createURI(id));
        }
        db.getEvents().fireBeforeSave(oldResource, resource, transaction);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        ((XMIResourceImpl) resource).doSave(os, options);
        byte[] content = os.toByteArray();
        Entity entity = new Entity(id, resource.getTimeStamp(), content);
        if (!isExists) {
            transaction.create(entity);
            id = entity.getId();
        }
        else {
            transaction.update(entity);
        }
        URI newURI = db.createURI(id, entity.getRev());
        resource.setURI(newURI);
        db.getEvents().fireAfterSave(oldResource, resource, transaction);
    }
}
