-keep public class * extends io.dcloud.weex.AppHookProxy{*;}
-keep public class * extends io.dcloud.feature.uniapp.UniAppHookProxy{*;}
-keep public class * extends io.dcloud.feature.uniapp.common.UniModule{*;}
-keep public class * extends io.dcloud.feature.uniapp.ui.component.UniComponent{*;}

-keepclassmembers class * extends io.dcloud.feature.uniapp.ui.component.UniComponent {
    @io.dcloud.feature.uniapp.annotation.UniJSMethod <methods>;
}