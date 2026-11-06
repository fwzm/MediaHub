using System.Text;
using MediaHub.Backup;

// 控制台输出固定为 UTF-8：Windows 的默认代码页（936）会让 --json 与中文文案在被重定向时变成一堆不可解析的字节，
// 脚本和 CI 无法直接按 UTF-8 读本工具的 stdout。设置 OutputEncoding 会同步替换 Console.Out 的编码。
Console.OutputEncoding = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false);

// 控制台外壳只有这一行：参数解析、退出码与全部中文文案都在 MhBackupCliCommand 里，
// 那边可以在单元测试里用任意 TextWriter 调用，不必真的起进程。
return MhBackupCliCommand.Run(args, Console.Out, Console.Error);
