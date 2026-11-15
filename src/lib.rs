use std::ffi::{c_char, c_void, CString};

/// JNI NewStringUTF table slot, stable in the JNI function table.
const JNI_NEW_STRING_UTF: usize = 167;

/// Android USB Host API enumerates and grants access. This Rust JNI bridge
/// only formats the interface classification and never accesses raw USB nodes.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_droidforge_mobile_MainActivity_nativeDescribeUsb(
    env: *mut *const *const c_void,
    _class: *mut c_void,
    vendor_id: i32,
    product_id: i32,
    adb: u8,
    fastboot: u8,
) -> *mut c_void {
    if env.is_null() || (*env).is_null() {
        return std::ptr::null_mut();
    }
    let text = if adb != 0 {
        format!("USB {:04x}:{:04x} — ADB interface detected", vendor_id, product_id)
    } else if fastboot != 0 {
        format!("USB {:04x}:{:04x} — Fastboot interface detected", vendor_id, product_id)
    } else {
        format!("USB {:04x}:{:04x} — ADB/Fastboot interface not recognized", vendor_id, product_id)
    };
    let Ok(text) = CString::new(text) else { return std::ptr::null_mut() };
    let function = *(*env).add(JNI_NEW_STRING_UTF);
    if function.is_null() {
        return std::ptr::null_mut();
    }
    let new_string: unsafe extern "system" fn(
        *mut *const *const c_void,
        *const c_char,
    ) -> *mut c_void = std::mem::transmute(function);
    new_string(env, text.as_ptr())
}
