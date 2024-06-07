const std = @import("std");
const SDL = @import("sdl2");

const Args = @import("./Args.zig");
const SimThread = @import("./SimThread.zig");

pub fn main() !void {
    var gpa = std.heap.GeneralPurposeAllocator(.{}){};
    defer _ = gpa.deinit();
    const alloc = gpa.allocator();

    var args = try Args.parse(alloc);
    defer args.deinit();

    var sim_thread = try SimThread.start(alloc, args.vcd_out);
    defer sim_thread.joinDeinit();

    try SDL.init(.{ .video = true, .events = true });
    defer SDL.quit();

    var window = try SDL.createWindow("ili9341spi", .default, .default, 320, 240, .{});
    defer window.destroy();

    var renderer = try SDL.createRenderer(window, null, .{ .accelerated = true });
    defer renderer.destroy();

    while (sim_thread.lockIfRunning()) {
        {
            defer sim_thread.unlock();

            while (SDL.pollEvent()) |ev| {
                switch (ev) {
                    .quit => sim_thread.halt(),
                    .key_down => |key| {
                        if (key.keycode == .escape) sim_thread.halt();
                    },
                    else => {},
                }
            }
        }

        try renderer.clear();

        renderer.present();
    }
}
