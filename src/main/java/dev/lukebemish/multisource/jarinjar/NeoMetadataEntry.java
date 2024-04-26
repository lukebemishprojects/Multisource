package dev.lukebemish.multisource.jarinjar;

import com.google.gson.JsonObject;

public record NeoMetadataEntry(NeoContainedVersion version, ModuleLocation identifier, String path, boolean isObfuscated) {
    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.add("version", version().toJson());
        json.add("identifier", identifier().toJson());
        json.addProperty("path", path());
        json.addProperty("isObfuscated", isObfuscated());
        return json;
    }
}
