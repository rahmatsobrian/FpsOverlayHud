// AIDL interface implemented by shizuku.UserService and run inside the
// Shizuku host process (shell/system UID), bound to from our app process.
package com.fpsoverlay.hud;

interface IUserService {
    String execCommand(String cmd);
    void destroy();
}
