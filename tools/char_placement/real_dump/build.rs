fn main() {
    // ncnn built with OpenMP; the scratch harness only needs the link flag.
    println!("cargo:rustc-link-lib=gomp");
}
