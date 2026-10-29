using MediaHub.Backup;

// 控制台外壳只有这一行：参数解析、退出码与全部中文文案都在 MhBackupCliCommand 里，
// 那边可以在单元测试里用任意 TextWriter 调用，不必真的起进程。
return MhBackupCliCommand.Run(args, Console.Out, Console.Error);
