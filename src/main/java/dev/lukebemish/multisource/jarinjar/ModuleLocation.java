package dev.lukebemish.multisource.jarinjar;

import com.google.gson.JsonObject;

public record ModuleLocation(String group, String artifact) {
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("group", group());
        json.addProperty("artifact", artifact());
        return json;
    }
}
