Usage:

1. Make a file `mxLogin` containing:
```
https://homeserver.org
@username:homeserver.org
password
```
2. Clone dzaima/BQN in this directory
3. Manually join the rooms where BQNBot should be in with its account
4. Activate wasi, including `wasmtime` on path
5. run `make -C path/to/dzaima/CBQN wasi-o3 OUTPUT="$PWD/CBQN"`
6. `./build`
7. `./run`

(not doing steps 4 & 5 will result in only `dbqn` commands working)