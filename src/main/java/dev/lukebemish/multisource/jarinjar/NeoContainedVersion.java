package dev.lukebemish.multisource.jarinjar;

import com.google.gson.JsonObject;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

public record NeoContainedVersion(VersionRange range, ArtifactVersion artifactVersion) {
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("range", range.toString());
        json.addProperty("artifactVersion", artifactVersion.toString());
        return json;
    }
}
