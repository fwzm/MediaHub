# Synthetic A4 playback fixture

`a4-synthetic.mp4` contains a generated solid 320x180 background, 15 fps H.264 baseline video and silent AAC audio for 60 seconds. It contains no user media. SHA256: `3d26077a00bec244b4201e58547b072b2ed3d8a2ec755dedfec90e308ba031e3`.

A corresponding fixture can be generated with FFmpeg (byte identity also depends on the encoder/build):

```text
ffmpeg -f lavfi -i color=c=0x183040:s=320x180:r=15:d=60 -f lavfi -i anullsrc=r=48000:cl=stereo -t 60 -c:v libx264 -profile:v baseline -pix_fmt yuv420p -c:a aac -movflags +faststart a4-synthetic.mp4
```

The test serves these pinned bytes from its own in-emulator MockWebServer, checks actual Media3 requests and runtime video dimensions, and creates its own SRT document. It does not establish real-server, physical-device, mpv graphics or OEM playback acceptance.
