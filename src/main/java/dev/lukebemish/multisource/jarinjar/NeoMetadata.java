package dev.lukebemish.multisource.jarinjar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

public record NeoMetadata(List<NeoMetadataEntry> jars) {
    JsonObject toJson() {
        JsonObject json = new JsonObject();
        JsonArray jars = new JsonArray();
        for (NeoMetadataEntry jar : jars()) {
            jars.add(jar.toJson());
        }
        json.add("jars", jars);
        return json;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public byte[] toJsonBytes() {
        return GSON.toJson(toJson()).getBytes(StandardCharsets.UTF_8);
    }
}
