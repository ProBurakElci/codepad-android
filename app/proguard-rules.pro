# The class the WebView calls into by name. R8 would otherwise rename the
# methods and every button in the app would silently stop working.
-keepclassmembers class io.github.proburakelci.codepad.MainActivity$Bridge {
    public *;
}
-keepattributes JavascriptInterface
