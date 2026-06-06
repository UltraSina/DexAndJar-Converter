Dex & Jar Converter
A native Android app that lets you convert between DEX, JAR, and Smali formats directly on your device. It runs the actual compiler tools locally without needing a PC.
What it does
DEX to JAR: Translates Android Dalvik bytecode back into standard Java .class files.
JAR to DEX: Compiles JAR files into Android DEX format using Google's D8 compiler (supports modern Java 8+ bytecode).
DEX to Smali: Disassembles a DEX file and outputs the Smali source code as a zipped folder.
Smali to DEX: Assembles a zipped folder containing Smali files back into an executable DEX.
Chained Conversions: Directly converts JAR to Smali, and Smali to JAR, by automatically handling the intermediate DEX steps.
