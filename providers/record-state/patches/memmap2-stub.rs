// Spike: on targets without mmap (WASI), "map" a file by reading it into memory.
// Read-only maps behave the same; writes to a mutable map never reach the file.
use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};

pub struct MmapInner {
    buf: Vec<u8>,
}

impl MmapInner {
    fn read(len: usize, mut file: &File, offset: u64) -> io::Result<MmapInner> {
        // A real map leaves the file's position alone; so must this.
        let position = file.stream_position()?;
        let mut buf = vec![0u8; len];
        file.seek(SeekFrom::Start(offset))?;
        let read = file.read_exact(&mut buf);
        file.seek(SeekFrom::Start(position))?;
        read.map(|()| MmapInner { buf })
    }

    pub fn map(len: usize, file: &File, offset: u64, _: bool, _: bool) -> io::Result<MmapInner> {
        Self::read(len, file, offset)
    }

    pub fn map_exec(len: usize, file: &File, offset: u64, _: bool, _: bool) -> io::Result<MmapInner> {
        Self::read(len, file, offset)
    }

    pub fn map_mut(_: usize, _: &File, _: u64, _: bool, _: bool) -> io::Result<MmapInner> {
        Err(io::ErrorKind::Unsupported.into()) // a write-through map cannot be emulated
    }

    pub fn map_copy(len: usize, file: &File, offset: u64, _: bool, _: bool) -> io::Result<MmapInner> {
        Self::read(len, file, offset)
    }

    pub fn map_copy_read_only(len: usize, file: &File, offset: u64, _: bool, _: bool) -> io::Result<MmapInner> {
        Self::read(len, file, offset)
    }

    pub fn map_anon(len: usize, _: bool, _: bool, _: Option<u8>, _: bool) -> io::Result<MmapInner> {
        Ok(MmapInner { buf: vec![0u8; len] })
    }

    pub fn flush(&self, _: usize, _: usize) -> io::Result<()> {
        Ok(())
    }

    pub fn flush_async(&self, _: usize, _: usize) -> io::Result<()> {
        Ok(())
    }

    pub fn make_read_only(&mut self) -> io::Result<()> {
        Ok(())
    }

    pub fn make_exec(&mut self) -> io::Result<()> {
        Ok(())
    }

    pub fn make_mut(&mut self) -> io::Result<()> {
        Ok(())
    }

    #[inline]
    pub fn ptr(&self) -> *const u8 {
        self.buf.as_ptr()
    }

    #[inline]
    pub fn mut_ptr(&mut self) -> *mut u8 {
        self.buf.as_mut_ptr()
    }

    #[inline]
    pub fn len(&self) -> usize {
        self.buf.len()
    }
}

pub fn file_len(file: &File) -> io::Result<u64> {
    Ok(file.metadata()?.len())
}
