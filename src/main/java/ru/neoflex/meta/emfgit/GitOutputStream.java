package ru.neoflex.meta.emfgit;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.URIConverter;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.eclipse.jgit.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

public class GitOutputStream extends ByteArrayOutputStream implements URIConverter.Saveable {
    private GitHandler handler;
    private URI uri;
    private Map<?, ?> options;

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
