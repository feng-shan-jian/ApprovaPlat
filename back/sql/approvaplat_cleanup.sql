-- 已初始化数据库的品牌清理脚本。
-- 新建数据库直接执行 ry_20260417.sql 即可，无需重复执行本脚本。

start transaction;

-- 先删除公告已读关系，避免已有环境中残留指向上游演示公告的记录。
delete notice_read
from sys_notice_read notice_read
inner join sys_notice notice on notice.notice_id = notice_read.notice_id
where notice.notice_title in (
  '温馨提醒：2018-07-01 若依新版本发布啦',
  '维护通知：2018-07-01 若依系统凌晨维护',
  '若依开源框架介绍'
);

delete from sys_notice
where notice_title in (
  '温馨提醒：2018-07-01 若依新版本发布啦',
  '维护通知：2018-07-01 若依系统凌晨维护',
  '若依开源框架介绍'
);

-- 删除角色关联后再删除官网菜单，保证菜单权限数据保持一致。
delete role_menu
from sys_role_menu role_menu
inner join sys_menu menu on menu.menu_id = role_menu.menu_id
where menu.menu_name = '若依官网'
   or menu.path in ('http://ruoyi.vip', 'https://ruoyi.vip');

delete from sys_menu
where menu_name = '若依官网'
   or path in ('http://ruoyi.vip', 'https://ruoyi.vip');

commit;
