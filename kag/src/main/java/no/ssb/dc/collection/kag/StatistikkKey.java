package no.ssb.dc.collection.kag;

import no.ssb.dc.collection.api.source.GenericKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StatistikkKey extends GenericKey {

    static final Map<String, Class<?>> keys = new LinkedHashMap<>();
    static final List<String> positionKeys = new ArrayList<>();

    static {
        keys.put("filename", String.class);
        keys.put("Fnr", String.class);

        positionKeys.add("Fnr");
    }

    public StatistikkKey() {
        super();
    }

    public StatistikkKey(Map<String, Object> values) {
        super(values);
    }

    @Override
    public Map<String, Class<?>> keys() {
        return keys;
    }

    @Override
    public List<String> positionKeys() {
        return positionKeys;
    }
}
