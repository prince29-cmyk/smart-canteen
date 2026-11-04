#!/bin/bash
# Compile and run Smart Canteen (Mac/Linux). Run from the project folder.
mkdir -p out
javac -cp "lib/*" -d out src/*.java && java -cp "out:lib/*" Main
