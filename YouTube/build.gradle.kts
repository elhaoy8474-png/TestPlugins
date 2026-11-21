version = 3

cloudstream {
    language = "en"
    authors = listOf("elhaoy8474-png")
    description = "YouTube search and public video playback with available captions"
    status = 1
    tvTypes = listOf("Movie")
    iconUrl = "https://www.youtube.com/s/desktop/95c3254f/img/favicon_144x144.png"
}

dependencies {
    // The host app ships NewPipeExtractor; compile against it without bundling a duplicate.
    compileOnly("com.github.teamnewpipe:NewPipeExtractor:v0.26.3")
    testImplementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.3")
    testImplementation("junit:junit:4.13.2")
}
