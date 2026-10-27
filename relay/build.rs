fn main() -> Result<(), Box<dyn std::error::Error>> {
    let proto_root = concat!(env!("CARGO_MANIFEST_DIR"), "/../proto");
    prost_build::Config::new()
        .compile_protos(&[format!("{proto_root}/pulse.proto")], &[proto_root])?;
    Ok(())
}
