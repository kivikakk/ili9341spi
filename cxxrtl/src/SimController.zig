const std = @import("std");

const SimThread = @import("./SimThread.zig");

const SimController = @This();

controller_alloc: std.mem.Allocator,
thread: std.Thread,
vcd_out: ?[]const u8,
mutex: std.Thread.Mutex = .{},
running: bool = true,
cycle_number: usize = 0,

pub fn start(alloc: std.mem.Allocator, vcd_out: ?[]const u8) !*SimController {
    var sim_controller = try alloc.create(SimController);
    sim_controller.* = .{
        .controller_alloc = alloc,
        .thread = undefined,
        .vcd_out = vcd_out,
    };
    const thread = try std.Thread.spawn(.{}, run, .{sim_controller});
    sim_controller.thread = thread;
    return sim_controller;
}

pub fn lockIfRunning(self: *SimController) bool {
    self.lock();
    if (!self.running) {
        self.unlock();
        return false;
    }
    return true;
}

pub fn lock(self: *SimController) void {
    self.mutex.lock();
}

pub fn unlock(self: *SimController) void {
    self.mutex.unlock();
}

pub fn cycleNumber(self: *const SimController) usize {
    return self.cycle_number;
}

pub fn halt(self: *SimController) void {
    self.running = false;
}

pub fn joinDeinit(self: *SimController) void {
    self.thread.join();
    self.controller_alloc.destroy(self);
}

fn run(sim_thread: *SimController) void {
    var gpa = std.heap.GeneralPurposeAllocator(.{}){};
    defer _ = gpa.deinit();
    const alloc = gpa.allocator();

    var state = SimThread.init(alloc, sim_thread);
    defer state.deinit();

    state.run() catch std.debug.panic("SimThread.State.run threw", .{});
}
