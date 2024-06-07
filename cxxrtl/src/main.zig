const std = @import("std");
const SDL = @import("sdl2");

const Cxxrtl = @import("./Cxxrtl.zig");
const SimThread = @import("./SimThread.zig");

pub fn main() !void {
    var gpa = std.heap.GeneralPurposeAllocator(.{}){};
    defer _ = gpa.deinit();
    const alloc = gpa.allocator();

    var vcd_out: ?[]const u8 = null;

    var args = try std.process.argsWithAllocator(alloc);
    defer args.deinit();

    _ = args.next();

    var arg_state: enum { root, vcd } = .root;
    while (args.next()) |arg| {
        switch (arg_state) {
            .root => {
                if (std.mem.eql(u8, arg, "--vcd"))
                    arg_state = .vcd
                else
                    std.debug.panic("unknown argument: \"{s}\"", .{arg});
            },
            .vcd => {
                vcd_out = arg;
                arg_state = .root;
            },
        }
    }
    if (arg_state != .root) std.debug.panic("missing argument for --vcd", .{});

    var sim_thread = try SimThread.start(vcd_out);

    try SDL.init(.{
        .video = true,
        .events = true,
    });
    defer SDL.quit();

    var window = try SDL.createWindow("ili9341spi", .default, .default, 320, 240, .{});
    defer window.destroy();

    var renderer = try SDL.createRenderer(window, null, .{ .accelerated = true });
    defer renderer.destroy();

    while (sim_thread.lockIfRunning()) {
        defer sim_thread.unlock();

        while (SDL.pollEvent()) |ev| {
            switch (ev) {
                .quit => sim_thread.halt(),
                else => {},
            }
        }

        try renderer.clear();

        renderer.present();
    }
}
