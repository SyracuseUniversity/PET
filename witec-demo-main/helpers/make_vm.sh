RG_NAME=witec-demo

az group create --name $RG_NAME --location eastus2

az vm create \
    --resource-group $RG_NAME \
    --name witec_vm \
    --image Ubuntu2204 \
    --admin-username az \
    --ssh-key-values witec_vm.pub \
    --public-ip-sku Standard \
    --location eastus2 \
    --size Standard_D2s_v3

az network nsg rule create \
    --name "AllowHTTP" \
    --resource-group $RG_NAME \
    --nsg-name witec_vmNSG \
    --priority 1100 \
    --protocol Tcp \
    --direction Inbound \
    --source-address-prefix "*" \
    --source-port-range "*" \
    --destination-port-range 80

az network nsg rule create \
    --name "AllowHTTPS" \
    --resource-group $RG_NAME \
    --nsg-name witec_vmNSG \
    --priority 1200 \
    --protocol Tcp \
    --direction Inbound \
    --source-address-prefix "*" \
    --source-port-range "*" \
    --destination-port-range 443


az vm list-ip-addresses -g $RG_NAME | jq -r '(..|.ipAddress? | select(. != null)) | "ip: " + .'