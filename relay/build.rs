fn main() -> Result<(), Box<dyn std::error::Error>> {
    let proto_root = concat!(env!("CARGO_MANIFEST_DIR"), "/../proto");
    println!("cargo:rerun-if-changed={proto_root}/pulse.proto");
    prost_build::Config::new()
        .compile_protos(&[format!("{proto_root}/pulse.proto")], &[proto_root])?;
    Ok(())
}
