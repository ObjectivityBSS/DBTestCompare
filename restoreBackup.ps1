docker ps -a
Write-Output "mkdir /var/opt/mssql/backup"
docker exec -i "sqlserver-container" mkdir "/var/opt/mssql/backup"
Write-Output "copy ./docker/AdventureWorks2008R2FullDatabaseBackup.bak to /var/opt/mssql/backup" 
docker cp "./docker/AdventureWorks2008R2FullDatabaseBackup.bak" sqlserver-container:/var/opt/mssql/backup
Write-Output "restore backup"
do	
{
	$JSON=docker inspect --format='{{json .State.Health}}'  sqlserver-container |  Out-String | ConvertFrom-Json
	Write-Host HEALTHCHECK: $JSON.Status.ToString() sqlserver-container
	Start-Sleep -s 10
}
Until ($JSON.Status.ToString() -eq 'healthy')
docker exec -i sqlserver-container /opt/mssql-tools/bin/sqlcmd -S localhost -U SA -P "yourStrong22Password" `
	-Q "RESTORE DATABASE AdventureWorks2008R2 FROM DISK = '/var/opt/mssql/backup/AdventureWorks2008R2FullDatabaseBackup.bak' `
	WITH MOVE 'AdventureWorks2008R2_Data' TO '/var/opt/mssql/data/AdventureWorks2008R2.mdf', `
	MOVE 'AdventureWorks2008R2_Log' TO '/var/opt/mssql/data/AdventureWorks2008R2_1.LDF'"