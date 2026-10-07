package com.secomes.netswitch;

interface IShellService {
    // Shizuku calls this transaction code to stop the user service.
    void destroy() = 16777114;

    // Runs argv without a shell. Returns "<exit code>\n<stdout+stderr>".
    String exec(in String[] argv) = 1;
}
