WITH root AS (
 INSERT INTO system_menus(name,permission,type,parent_id,path,component,icon,sort,status,created_at,updated_at)
 VALUES('表情管理','',1,0,'/sticker',NULL,'ant-design:smile-outlined',35,1,0,0) RETURNING id
), pages AS (
 INSERT INTO system_menus(name,permission,type,parent_id,path,component,sort,status,created_at,updated_at)
 SELECT v.name,v.permission,2,root.id,v.path,v.component,v.sort,1,0,0 FROM root CROSS JOIN
 (VALUES ('表情包','sticker:pack:query','pack','sticker/pack/index',1),('表情作者','sticker:author:query','author','sticker/author/index',2)) v(name,permission,path,component,sort)
 RETURNING id,path
), buttons AS (
 INSERT INTO system_menus(name,permission,type,parent_id,path,component,sort,status,created_at,updated_at)
 SELECT v.name,'sticker:'||pages.path||':'||v.action,3,pages.id,'',NULL,v.sort,1,0,0 FROM pages JOIN
 (VALUES ('pack','新增','create',1),('pack','编辑','update',2),('pack','删除','delete',3),('pack','发布','publish',4),('pack','下架','offline',5),('pack','导入','import',6),('pack','制作','make',7),('author','新增','create',1),('author','编辑','update',2),('author','删除','delete',3)) v(resource,name,action,sort) ON pages.path=v.resource RETURNING id
)
SELECT COUNT(*) FROM buttons;
