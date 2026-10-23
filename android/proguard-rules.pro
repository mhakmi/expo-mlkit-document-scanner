# ML Kit finds its component registrars by name and builds them through reflection.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
  public <init>();
}
