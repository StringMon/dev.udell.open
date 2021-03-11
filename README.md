# dev.udell.open

General-use open-source Android code written by Sterling Udell.

The easiest way to use this library is via JitPack. In your root `build.gradle`:

	allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}

...and then in your project-level `build.gradle`:

	dependencies {
		implementation 'com.github.Stringmon:dev.udell.open:trunk-SNAPSHOT'
	}
