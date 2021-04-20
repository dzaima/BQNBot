mkdir BQNb
cp -r BQN/src BQNb/src
cp -r BQN/build BQNb/build
cd BQNb
sed -ie 's/boolean SAFE = false;/boolean SAFE = true;/' src/BQN/Main.java
./build
cd ..
mv BQNb/BQN.jar lib/BQN.jar
rm -r BQNb
