package ru.neoflex.meta.emfgit;

public class EntityId {
    private String id;
    private long rev;

    public EntityId(String id) {
        this(id, 0);
    }

    public EntityId(String id, long rev) {
        this.id = id;
        this.rev = rev;
    }

    public EntityId() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String[] getIdPath() {
        return id.split("/");
    }

    public long getRev() {
        return rev;
    }

    public void setRev(long rev) {
        this.rev = rev;
    }
}
